package com.example.demo.business;

import com.example.demo.dto.CreateColumnDefinitionRequest;
import com.example.demo.dto.UpdateColumnDefinitionRequest;
import com.example.demo.exception.InvalidColumnDefinitionException;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.Template;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.service.CodebookService;
import com.example.demo.service.ColumnDefinitionService;
import com.example.demo.service.LogService;
import com.example.demo.service.SchemaValidator;
import com.example.demo.service.TemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - pravila definicije stupca koja backend mora braniti i za izravne
 * API pozive (frontend ih vec provjerava): tip mora biti poznat, a sifrarnik i tip
 * "codebook" idu iskljucivo zajedno.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class ColumnDefinitionRulesTest {

    private static final long TEMPLATE_ID = 10L;

    @Mock
    private TemplateService templateService;

    @Mock
    private SurveyResultRepository surveyRepository;

    @Mock
    private SchemaValidator validator;

    @Mock
    private CodebookService codebookService;

    @Mock
    private LogService logService;

    @InjectMocks
    private ColumnDefinitionService service;

    private Template template(ColumnEntry... entries) {
        Template template = new Template(3L, "Obrazac");
        template.setId(TEMPLATE_ID);
        template.setDefinitions(new ArrayList<>(List.of(entries)));
        return template;
    }

    // Stupac bez dodatnih atributa - ovi testovi ispituju tip i vezu na sifrarnik,
    // pa label/required/unique/defaultValue/readOnly ostaju prazni.
    private CreateColumnDefinitionRequest newColumn(String key, String type, Long codebookId) {
        return new CreateColumnDefinitionRequest(key, type, codebookId, null, false, false, null, false);
    }

    private UpdateColumnDefinitionRequest changedTo(String key, String type, Long codebookId) {
        return new UpdateColumnDefinitionRequest(key, type, codebookId, null, false, false, null, false);
    }

    @Test
    @DisplayName("create odbija nepoznat tip stupca")
    void createRejectsUnknownType() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatThrownBy(() -> service.create(TEMPLATE_ID, newColumn("x", "banana", null)))
                .isInstanceOf(InvalidColumnDefinitionException.class);

        verify(templateService, never()).save(any());
    }

    /**
     * Ukinuti tipovi se ne smiju provuci ni "starim" zahtjevom: nakon migracije u bazi
     * vise nema takvog stupca, pa bi ga novi propustio natrag u shemu.
     */
    @Test
    @DisplayName("create odbija ukinute tipove select i boolean")
    void createRejectsRetiredTypes() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatThrownBy(() -> service.create(TEMPLATE_ID, newColumn("status", "select", null)))
                .isInstanceOf(InvalidColumnDefinitionException.class);
        assertThatThrownBy(() -> service.create(TEMPLATE_ID, newColumn("remote", "boolean", null)))
                .isInstanceOf(InvalidColumnDefinitionException.class);

        verify(templateService, never()).save(any());
    }

    @Test
    @DisplayName("create odbija Å¡ifrarniÄki stupac bez Å¡ifrarnika")
    void createRejectsCodebookColumnWithoutCodebook() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatThrownBy(() -> service.create(TEMPLATE_ID, newColumn("status", "codebook", null)))
                .isInstanceOf(InvalidColumnDefinitionException.class);

        verify(templateService, never()).save(any());
    }

    @Test
    @DisplayName("create prihvaÄ‡a Å¡ifrarniÄki stupac s odabranim Å¡ifrarnikom")
    void createAcceptsCodebookColumn() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatCode(() -> service.create(TEMPLATE_ID, newColumn("status", "codebook", 7L)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("update odbija nepoznat tip stupca")
    void updateRejectsUnknownType() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(new ColumnEntry("grad", "string", null)));

        assertThatThrownBy(() -> service.update(TEMPLATE_ID, "grad",
                changedTo("grad", "banana", null)))
                .isInstanceOf(InvalidColumnDefinitionException.class);

        verify(templateService, never()).save(any());
    }

    /** Sifrarnik na stupcu koji nije sifrarnicki nitko ne bi citao - ostao bi tiha neistina u shemi. */
    @Test
    @DisplayName("update odbija Å¡ifrarnik na stupcu koji nije Å¡ifrarniÄki")
    void updateRejectsCodebookOnPlainColumn() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(new ColumnEntry("grad", "string", null)));

        assertThatThrownBy(() -> service.update(TEMPLATE_ID, "grad",
                changedTo("grad", "string", 7L)))
                .isInstanceOf(InvalidColumnDefinitionException.class);

        verify(templateService, never()).save(any());
    }
}
