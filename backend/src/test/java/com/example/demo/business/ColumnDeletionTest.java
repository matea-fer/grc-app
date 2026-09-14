package com.example.demo.business;

import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.service.AttachmentCleanup;
import com.example.demo.service.ColumnDefinitionService;
import com.example.demo.service.LogService;
import com.example.demo.service.RecordChangeService;
import com.example.demo.service.SchemaValidator;
import com.example.demo.service.TemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - sto se dogada s VRIJEDNOSTIMA kad stupac nestane iz templatea.
 *
 * Odluceno: brisanje stupca brise i vrijednosti tog kljuca iz zapisa TOG templatea,
 * sve u jednoj transakciji. Zapisi drugih templatea se ne diraju.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class ColumnDeletionTest {

    private static final long TEMPLATE_ID = 10L;

    @Mock
    private TemplateService templateService;

    @Mock
    private SurveyResultRepository surveyRepository;

    @Mock
    private SchemaValidator validator;

    @Mock
    private LogService logService;

    @Mock
    private AttachmentCleanup attachmentCleanup;

    /** Brisanje stupca od 12.08. upisuje izgubljenu vrijednost u povijest retka. */
    @Mock
    private RecordChangeService recordChangeService;

    @InjectMocks
    private ColumnDefinitionService service;

    private Template template(String... keys) {
        Template template = new Template(3L, "Obrazac");
        template.setId(TEMPLATE_ID);
        template.setDefinitions(Arrays.stream(keys)
                .map(key -> new ColumnEntry(key, "string", null))
                .collect(Collectors.toCollection(ArrayList::new)));
        return template;
    }

    private SurveyResult survey(Long id, Map<String, Object> data) {
        SurveyResult survey = new SurveyResult();
        survey.setId(id);
        survey.setCompanyId(3L);
        survey.setTemplateId(TEMPLATE_ID);
        survey.setData(new HashMap<>(data));
        return survey;
    }

    private List<SurveyResult> savedSurveys() {
        ArgumentCaptor<SurveyResult> captor = ArgumentCaptor.forClass(SurveyResult.class);
        verify(surveyRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("brisanje stupca mice tu vrijednost iz zapisa templatea, ostale ostaju")
    void deletingColumnClearsItsValues() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template("grad", "tim"));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of(
                survey(1L, Map.of("grad", "Split", "tim", "backend"))
        ));

        service.delete(TEMPLATE_ID, "grad");

        assertThat(savedSurveys()).singleElement()
                .satisfies(saved -> assertThat(saved.getData())
                        .doesNotContainKey("grad")
                        .containsEntry("tim", "backend"));
    }

    @Test
    @DisplayName("brisanje pada kad stupac nije deklariran u templateu")
    void deletingUndeclaredColumnFails() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template("tim"));

        assertThatThrownBy(() -> service.delete(TEMPLATE_ID, "grad"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(surveyRepository, never()).save(any());
    }

    @Test
    @DisplayName("zapisi koji taj stupac nikad nisu popunili se ne diraju")
    void surveysWithoutTheKeyAreLeftAlone() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template("grad"));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of(
                survey(1L, Map.of("tim", "backend"))
        ));

        service.delete(TEMPLATE_ID, "grad");

        // nepotreban upis bi bez razloga podigao verziju retka
        verify(surveyRepository, never()).save(any());
    }

    @Test
    @DisplayName("brisanje dira samo zapise ovog templatea")
    void deletingTouchesOnlyThisTemplatesSurveys() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template("grad"));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of(
                survey(1L, Map.of("grad", "Split"))
        ));

        service.delete(TEMPLATE_ID, "grad");

        // trazi se samo po templateId - zapisi ostalih templatea se ni ne ucitavaju
        verify(surveyRepository, never()).findAll();
        verify(surveyRepository).findByTemplateId(TEMPLATE_ID);
    }
}
