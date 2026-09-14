package com.example.demo.core;

import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.dto.CreateColumnDefinitionRequest;
import com.example.demo.dto.UpdateColumnDefinitionRequest;
import com.example.demo.exception.DuplicateColumnKeyException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.Template;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.service.AttachmentCleanup;
import com.example.demo.service.CodebookService;
import com.example.demo.service.ColumnDefinitionService;
import com.example.demo.service.LogService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CORE test - generika nad stupcima jednog templatea: mapiranje request -> zapis
 * -> response, 404 kad stupac ne postoji i cuvanje jedinstvenosti kljuca.
 *
 * Vlasnistvo templatea provjerava {@link TemplateService} (mockiran) - ovdje se
 * pretpostavlja da je template firmin i vraca se iz {@code requireOwned/requireEditable}.
 */
@Tag("core")
@ExtendWith(MockitoExtension.class)
class ColumnDefinitionServiceTest {

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

    @Mock
    private AttachmentCleanup attachmentCleanup;

    @InjectMocks
    private ColumnDefinitionService service;

    private Template template(ColumnEntry... entries) {
        Template template = new Template(3L, "Obrazac");
        template.setId(TEMPLATE_ID);
        template.setDefinitions(new ArrayList<>(List.of(entries)));
        return template;
    }

    // Stupac bez dodatnih atributa - ovdje se testira mapiranje i jedinstvenost kljuca,
    // pa label/required/unique/defaultValue/readOnly ostaju prazni.
    private CreateColumnDefinitionRequest newColumn(String key, String type, Long codebookId) {
        return new CreateColumnDefinitionRequest(key, type, codebookId, null, false, false, null, false);
    }

    private UpdateColumnDefinitionRequest changedTo(String key, String type, Long codebookId) {
        return new UpdateColumnDefinitionRequest(key, type, codebookId, null, false, false, null, false);
    }

    private Template savedTemplate() {
        ArgumentCaptor<Template> captor = ArgumentCaptor.forClass(Template.class);
        verify(templateService).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("create dodaje stupac u template i vraca ga u odgovoru")
    void createAddsEntryToTemplate() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());
        var request = newColumn("grad", "codebook", 7L);

        ColumnDefinitionResponse response = service.create(TEMPLATE_ID, request);

        assertThat(savedTemplate().getDefinitions())
                .containsExactly(new ColumnEntry("grad", "codebook", 7L));
        assertThat(response.columnKey()).isEqualTo("grad");
        assertThat(response.columnType()).isEqualTo("codebook");
        assertThat(response.codebookId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("create cuva vec postojece stupce templatea")
    void createKeepsExistingColumns() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(new ColumnEntry("tim", "string", null)));

        service.create(TEMPLATE_ID, newColumn("grad", "string", null));

        assertThat(savedTemplate().getDefinitions())
                .extracting(ColumnEntry::key)
                .containsExactly("tim", "grad");
    }

    @Test
    @DisplayName("create odbija stupac s nazivom koji template vec ima")
    void createRejectsDuplicateKey() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(new ColumnEntry("grad", "string", null)));

        assertThatThrownBy(() -> service.create(TEMPLATE_ID, newColumn("grad", "string", null)))
                .isInstanceOf(DuplicateColumnKeyException.class);

        verify(templateService, never()).save(any());
    }

    @Test
    @DisplayName("update preimenuje stupac zadrzavajuci mu poziciju")
    void updateRenamesColumnKeepingPosition() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(
                new ColumnEntry("tim", "string", null),
                new ColumnEntry("grad", "string", null)));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of());

        ColumnDefinitionResponse response = service.update(TEMPLATE_ID, "grad",
                changedTo("mjesto", "string", null));

        assertThat(savedTemplate().getDefinitions())
                .extracting(ColumnEntry::key)
                .containsExactly("tim", "mjesto");
        assertThat(response.columnKey()).isEqualTo("mjesto");
    }

    @Test
    @DisplayName("update odbija preimenovanje u naziv koji template vec ima")
    void updateRejectsRenameToExistingKey() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(
                new ColumnEntry("tim", "string", null),
                new ColumnEntry("grad", "string", null)));

        assertThatThrownBy(() -> service.update(TEMPLATE_ID, "grad",
                changedTo("tim", "string", null)))
                .isInstanceOf(DuplicateColumnKeyException.class);

        verify(templateService, never()).save(any());
    }

    @Test
    @DisplayName("update baca ResourceNotFoundException kad stupac ne postoji")
    void updateThrowsWhenColumnMissing() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(new ColumnEntry("tim", "string", null)));

        assertThatThrownBy(() -> service.update(TEMPLATE_ID, "grad",
                changedTo("grad", "string", null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(templateService, never()).save(any());
    }

    @Test
    @DisplayName("delete mice samo trazeni stupac, ostali ostaju")
    void deleteRemovesOnlyRequestedColumn() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(
                new ColumnEntry("tim", "string", null),
                new ColumnEntry("grad", "string", null),
                new ColumnEntry("remote", "boolean", null)));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of());

        service.delete(TEMPLATE_ID, "grad");

        assertThat(savedTemplate().getDefinitions())
                .extracting(ColumnEntry::key)
                .containsExactly("tim", "remote");
    }

    @Test
    @DisplayName("delete baca ResourceNotFoundException kad stupac nije deklariran")
    void deleteThrowsWhenKeyMissing() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(new ColumnEntry("tim", "string", null)));

        assertThatThrownBy(() -> service.delete(TEMPLATE_ID, "grad"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(templateService, never()).save(any());
    }

    @Test
    @DisplayName("getForTemplate vraca stupce templatea")
    void getForTemplateReturnsColumns() {
        when(templateService.requireOwned(TEMPLATE_ID)).thenReturn(template(
                new ColumnEntry("grad", "string", null),
                new ColumnEntry("remote", "boolean", null)));

        assertThat(service.getForTemplate(TEMPLATE_ID))
                .extracting(ColumnDefinitionResponse::columnKey)
                .containsExactly("grad", "remote");
    }
}
