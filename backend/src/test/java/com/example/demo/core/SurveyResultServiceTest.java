package com.example.demo.core;

import com.example.demo.auth.AuthContext;
import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.dto.CreateSurveyRequest;
import com.example.demo.dto.PageResponse;
import com.example.demo.dto.SurveyResponse;
import com.example.demo.dto.UpdateSurveyRequest;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.repository.SurveyQueryRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.service.ReferenceLookup;
import com.example.demo.service.AttachmentCleanup;
import com.example.demo.service.CodebookItemService;
import com.example.demo.service.ColumnDefinitionService;
import com.example.demo.service.ColumnSequenceService;
import com.example.demo.service.FormulaEvaluator;
import com.example.demo.service.LogService;
import com.example.demo.service.RecordChangeService;
import com.example.demo.service.SchemaValidator;
import com.example.demo.service.SurveyResultService;
import com.example.demo.service.TemplateService;
import com.example.demo.service.UniqueValueLookup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CORE test - generika nad SurveyResultService: mapiranje DTO -> entitet -> DTO,
 * 404 na nepostojeci id, PUT semantika pune zamjene i obrana od null podataka.
 *
 * Zapisi su vezani uz templateId iz putanje. Vlasnistvo templatea (pripada li
 * firmi iz konteksta) provjerava {@link TemplateService} (mockiran); ovdje se
 * testira izolacija na razini zapisa: zapis drugog templatea se tretira kao 404.
 *
 * Sama pravila validacije se ovdje NE testiraju (validator je mockiran).
 */
@Tag("core")
@ExtendWith(MockitoExtension.class)
class SurveyResultServiceTest {

    private static final long TEMPLATE_ID = 10L;

    @Mock
    private SurveyResultRepository repository;

    @Mock
    private SurveyQueryRepository queryRepository;

    @Mock
    private ColumnDefinitionService columnDefinitionService;

    @Mock
    private SchemaValidator validator;

    @Mock
    private TemplateService templateService;

    @Mock
    private ColumnSequenceService columnSequenceService;

    @Mock
    private CodebookItemService codebookItemService;

    /**
     * Formule su pravi razred, a ne mock: {@code computeAll} vraca podatke retka, pa bi mock
     * na svakom spremanju vratio null i srusio testove koji s formulama nemaju veze. Prazna
     * shema kroz njega prolazi nedirnuta, sto je ovdje tocno ono sto treba.
     */
    @Spy
    private FormulaEvaluator formulaEvaluator = new FormulaEvaluator();

    @Mock
    private LogService logService;

    @Mock
    private AttachmentCleanup attachmentCleanup;

    @Mock
    private RecordChangeService recordChangeService;

    /**
     * Ovdje se ne koristi ni u jednom testu - treba samo da ga {@link InjectMocks} preda
     * konstruktoru. Zakljucavanje (jedino sto ga cita) je poslovno pravilo i testira se u
     * {@code RecordLockTest}.
     */
    @Mock
    private AuthContext authContext;

    /**
     * Isto: ovdje samo da konstruktor dobije sve. Pravila veza (koga brisanje smije odnijeti,
     * a koga ne) su poslovna i testiraju se u {@code ReferenceDeleteRulesTest}; ovdje mock
     * vraca prazan popis, dakle "nitko ne pokazuje na ovaj zapis".
     */
    @Mock
    private ReferenceLookup referenceLookup;

    @InjectMocks
    private SurveyResultService service;

    /** Template koji requireOwned vraca - kao da je vec potvrden kao firmin. */
    private void templateOwned() {
        Template template = new Template(3L, "Obrazac");
        template.setId(TEMPLATE_ID);
        lenient().when(templateService.requireOwned(TEMPLATE_ID)).thenReturn(template);
    }

    private void saveReturnsArgumentWithId(Long id) {
        when(repository.save(any(SurveyResult.class))).thenAnswer(invocation -> {
            SurveyResult toSave = invocation.getArgument(0);
            toSave.setId(id);
            return toSave;
        });
    }

    private SurveyResult existingSurvey(Long templateId) {
        SurveyResult existing = new SurveyResult();
        existing.setId(1L);
        existing.setCompanyId(3L);
        existing.setTemplateId(templateId);
        existing.setData(new HashMap<>(Map.of("grad", "Split")));
        return existing;
    }

    @Test
    @DisplayName("create postavlja template i firmu iz njega te cuva podatke")
    void createSetsTemplateAndCompanyAndStoresData() {
        templateOwned();
        var request = new CreateSurveyRequest(Map.of("grad", "Split", "remote", true));
        saveReturnsArgumentWithId(1L);

        SurveyResponse response = service.create(TEMPLATE_ID, request);

        ArgumentCaptor<SurveyResult> captor = ArgumentCaptor.forClass(SurveyResult.class);
        verify(repository).save(captor.capture());
        SurveyResult saved = captor.getValue();
        assertThat(saved.getTemplateId()).isEqualTo(TEMPLATE_ID);
        assertThat(saved.getCompanyId()).isEqualTo(3L);
        assertThat(saved.getData()).containsEntry("grad", "Split").containsEntry("remote", true);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.templateId()).isEqualTo(TEMPLATE_ID);
    }

    @Test
    @DisplayName("create pretvara null data u prazan Map umjesto da ga ostavi null")
    void createDefaultsNullDataToEmptyMap() {
        templateOwned();
        var request = new CreateSurveyRequest(null);
        saveReturnsArgumentWithId(1L);

        SurveyResponse response = service.create(TEMPLATE_ID, request);

        assertThat(response.data()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("create validira podatke prema shemi templatea")
    void createValidatesAgainstTemplateSchema() {
        templateOwned();
        List<ColumnDefinitionResponse> schema = List.of(new ColumnDefinitionResponse("grad", "string", null));
        when(columnDefinitionService.getForTemplate(TEMPLATE_ID)).thenReturn(schema);
        saveReturnsArgumentWithId(1L);

        service.create(TEMPLATE_ID, new CreateSurveyRequest(Map.of("grad", "Split")));

        // treci argument je pitanje o jedinstvenosti; na njega odgovara baza, pa se ovdje
        // provjerava samo da su podaci i shema predani validatoru
        verify(validator).validate(eq(Map.of("grad", "Split")), eq(schema), any(UniqueValueLookup.class));
    }

    @Test
    @DisplayName("getOne vraca mapirani odgovor kad zapis postoji i pripada templateu")
    void getOneReturnsMappedResponse() {
        templateOwned();
        when(repository.findById(1L)).thenReturn(Optional.of(existingSurvey(TEMPLATE_ID)));

        SurveyResponse response = service.getOne(TEMPLATE_ID, 1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.templateId()).isEqualTo(TEMPLATE_ID);
        assertThat(response.data()).containsEntry("grad", "Split");
    }

    @Test
    @DisplayName("getOne baca ResourceNotFoundException kad zapis ne postoji")
    void getOneThrowsWhenMissing() {
        templateOwned();
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOne(TEMPLATE_ID, 999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getOne baca 404 za zapis drugog templatea - ne otkriva da postoji")
    void getOneHidesOtherTemplatesSurvey() {
        templateOwned();
        // zapis pripada templateu 99, a trazi se pod templateom 10
        when(repository.findById(1L)).thenReturn(Optional.of(existingSurvey(99L)));

        assertThatThrownBy(() -> service.getOne(TEMPLATE_ID, 1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("update u cijelosti zamjenjuje podatke umjesto da ih spaja sa starima")
    void updateReplacesDataInsteadOfMerging() {
        templateOwned();
        when(repository.findById(1L)).thenReturn(Optional.of(existingSurvey(TEMPLATE_ID)));
        saveReturnsArgumentWithId(1L);

        SurveyResponse response = service.update(TEMPLATE_ID, 1L, new UpdateSurveyRequest(Map.of("tim", "QA")));

        assertThat(response.data()).containsEntry("tim", "QA").doesNotContainKey("grad");
    }

    @Test
    @DisplayName("update baca 404 prije validacije kad zapis ne postoji")
    void updateThrowsWhenMissing() {
        templateOwned();
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(TEMPLATE_ID, 999L, new UpdateSurveyRequest(Map.of())))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).save(any());
        verify(validator, never()).validate(any(), anyList(), any(UniqueValueLookup.class));
    }

    @Test
    @DisplayName("update baca 404 za zapis drugog templatea i nista ne mijenja")
    void updateHidesOtherTemplatesSurvey() {
        templateOwned();
        when(repository.findById(1L)).thenReturn(Optional.of(existingSurvey(99L)));

        assertThatThrownBy(() -> service.update(TEMPLATE_ID, 1L, new UpdateSurveyRequest(Map.of("tim", "QA"))))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("delete baca ResourceNotFoundException i nista ne brise kad zapis ne postoji")
    void deleteThrowsWhenMissing() {
        templateOwned();
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(TEMPLATE_ID, 999L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).deleteById(any());
    }

    @Test
    @DisplayName("delete baca 404 za zapis drugog templatea i nista ne brise")
    void deleteHidesOtherTemplatesSurvey() {
        templateOwned();
        when(repository.findById(1L)).thenReturn(Optional.of(existingSurvey(99L)));

        assertThatThrownBy(() -> service.delete(TEMPLATE_ID, 1L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).deleteById(any());
    }

    @Test
    @DisplayName("delete brise kad zapis postoji i pripada templateu")
    void deleteRemovesWhenExists() {
        templateOwned();
        when(repository.findById(1L)).thenReturn(Optional.of(existingSurvey(TEMPLATE_ID)));

        service.delete(TEMPLATE_ID, 1L);

        verify(repository).deleteById(1L);
    }

    @Test
    @DisplayName("getAll vraca stranicu zapisa trazenog templatea, s ukupnim brojem")
    void getAllReturnsPageOfTemplatesSurveys() {
        templateOwned();
        when(queryRepository.count(eq(TEMPLATE_ID), eq(List.of()), isNull())).thenReturn(1L);
        when(queryRepository.find(eq(TEMPLATE_ID), eq(List.of()), isNull(), isNull(), eq(false), eq(false), eq(0), anyInt()))
                .thenReturn(List.of(existingSurvey(TEMPLATE_ID)));

        PageResponse<SurveyResponse> pageResponse = service.getAll(TEMPLATE_ID, 0, null, null, null);

        assertThat(pageResponse.content()).hasSize(1);
        assertThat(pageResponse.content().getFirst().templateId()).isEqualTo(TEMPLATE_ID);
        assertThat(pageResponse.totalElements()).isEqualTo(1);
        assertThat(pageResponse.totalPages()).isEqualTo(1);
    }
}
