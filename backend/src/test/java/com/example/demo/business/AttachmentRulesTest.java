package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.AttachmentResponse;
import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.exception.InvalidAttachmentException;
import com.example.demo.exception.RecordLockedException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Attachment;
import com.example.demo.model.ColumnOptions;
import com.example.demo.model.Role;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.repository.AttachmentContentRepository;
import com.example.demo.repository.AttachmentRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.service.AttachmentCleanup;
import com.example.demo.service.AttachmentService;
import com.example.demo.service.ColumnDefinitionService;
import com.example.demo.service.LogService;
import com.example.demo.service.RecordChangeService;
import com.example.demo.service.TemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - pravila prilaganja datoteka.
 *
 * Prilog je PODATAK, pa ga smije dodati svatko u firmi (kao i zapis) - ali samo ondje gdje
 * ima smisla stajati. Zato se ovdje ne testira toliko uspjesan prijenos koliko sve ono sto
 * mora biti odbijeno: tudi zapis, stupac koji nije za datoteke, prazna i prevelika datoteka.
 *
 * Sadrzaj datoteke ide u vlastitu tablicu; da je u istoj, svako listanje priloga (a crta se
 * uz svaki redak) povuklo bi i sve datoteke iz baze.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class AttachmentRulesTest {

    private static final long TEMPLATE_ID = 10L;
    private static final long SURVEY_ID = 5L;

    @Mock
    private AttachmentRepository repository;

    @Mock
    private AttachmentContentRepository contentRepository;

    @Mock
    private SurveyResultRepository surveyRepository;

    @Mock
    private ColumnDefinitionService columnDefinitionService;

    @Mock
    private TemplateService templateService;

    @Mock
    private AttachmentCleanup cleanup;

    @Mock
    private AuthContext authContext;

    @Mock
    private LogService logService;

    @Mock
    private RecordChangeService recordChangeService;

    private AttachmentService service;

    @BeforeEach
    void setUp() {
        // granica od 1 MB, da se prevelika datoteka moze napraviti bez megabajta u testu
        service = new AttachmentService(repository, contentRepository, surveyRepository,
                columnDefinitionService, templateService, cleanup, authContext, logService,
                recordChangeService, 1024);
        lenient().when(authContext.require())
                .thenReturn(new AuthenticatedUser(1L, "ana", Role.USER, 3L));
    }

    private void templateOwned() {
        Template template = new Template(3L, "Obrazac");
        template.setId(TEMPLATE_ID);
        lenient().when(templateService.requireOwned(TEMPLATE_ID)).thenReturn(template);
    }

    private void surveyBelongsTo(long templateId) {
        SurveyResult survey = new SurveyResult();
        survey.setId(SURVEY_ID);
        survey.setTemplateId(templateId);
        when(surveyRepository.findById(SURVEY_ID)).thenReturn(Optional.of(survey));
    }

    /** Zapis koji postoji, pripada obrascu i zakljucan je (od 12.08.). */
    private void lockedSurvey() {
        SurveyResult survey = new SurveyResult();
        survey.setId(SURVEY_ID);
        survey.setTemplateId(TEMPLATE_ID);
        survey.setLockedAt(Instant.parse("2026-08-12T09:00:00Z"));
        survey.setLockedBy("ana");
        when(surveyRepository.findById(SURVEY_ID)).thenReturn(Optional.of(survey));
    }

    private void schemaHas(ColumnDefinitionResponse... columns) {
        when(columnDefinitionService.getForTemplate(TEMPLATE_ID)).thenReturn(List.of(columns));
    }

    private static ColumnDefinitionResponse fileColumn(String key) {
        return new ColumnDefinitionResponse(key, "file", null, null, false, false, null, false, null,
                new ColumnOptions(false, null, null, null, null, null, null, null, false, false,
                        null, "Učitaj dokument", null));
    }

    private void saveReturnsWithId() {
        when(repository.save(any(Attachment.class))).thenAnswer(call -> {
            Attachment attachment = call.getArgument(0);
            attachment.setId(77L);
            return attachment;
        });
    }

    private Attachment savedAttachment() {
        ArgumentCaptor<Attachment> captor = ArgumentCaptor.forClass(Attachment.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "application/pdf", content.getBytes());
    }

    // ===================== PRILAGANJE =====================

    @Test
    @DisplayName("datoteka se prilaze uz zapis i stupac, s podacima o tome tko ju je dodao")
    void uploadStoresMetadataAndContent() {
        templateOwned();
        surveyBelongsTo(TEMPLATE_ID);
        schemaHas(fileColumn("ponuda"));
        saveReturnsWithId();

        AttachmentResponse response = service.upload(TEMPLATE_ID, SURVEY_ID, "ponuda", file("ponuda.pdf", "sadrzaj"));

        Attachment saved = savedAttachment();
        assertThat(saved.getCompanyId()).isEqualTo(3L);
        assertThat(saved.getSurveyId()).isEqualTo(SURVEY_ID);
        assertThat(saved.getColumnKey()).isEqualTo("ponuda");
        assertThat(saved.getFileName()).isEqualTo("ponuda.pdf");
        assertThat(saved.getUploadedBy()).isEqualTo("ana");
        assertThat(response.sizeBytes()).isEqualTo("sadrzaj".getBytes().length);

        // sadrzaj ide u vlastitu tablicu, pod istim id-em
        verify(contentRepository).save(any());
    }

    /**
     * Dnevnik biljezi DA je netko prilozio datoteku; povijest zapisa biljezi da je taj REDAK
     * dobio dokaz. To je drugo pitanje i drugi ekran - onaj koji gleda kako je zapis postao
     * ovakav ne otvara dnevnik cijele firme.
     */
    @Test
    @DisplayName("prilaganje ulazi u povijest zapisa, ne samo u dnevnik")
    void uploadEntersRecordHistory() {
        templateOwned();
        surveyBelongsTo(TEMPLATE_ID);
        schemaHas(fileColumn("ponuda"));
        saveReturnsWithId();

        service.upload(TEMPLATE_ID, SURVEY_ID, "ponuda", file("ponuda.pdf", "sadrzaj"));

        verify(recordChangeService).attachmentAdded(SURVEY_ID, TEMPLATE_ID, 3L, "ponuda", "ponuda.pdf");
    }

    @Test
    @DisplayName("uklanjanje priloga ulazi u povijest zapisa")
    void deleteEntersRecordHistory() {
        templateOwned();
        surveyBelongsTo(TEMPLATE_ID);
        Attachment attachment = new Attachment(3L, TEMPLATE_ID, SURVEY_ID, "ponuda", "ponuda.pdf", null, 10, "ana");
        attachment.setId(77L);
        when(repository.findById(77L)).thenReturn(Optional.of(attachment));

        service.delete(TEMPLATE_ID, 77L);

        verify(recordChangeService).attachmentRemoved(SURVEY_ID, TEMPLATE_ID, 3L, "ponuda", "ponuda.pdf");
    }

    /**
     * Zakljucavanje koje bi vrijedilo samo za vrijednosti bilo bi poluzastita: dokazi se
     * uz kontrolu PRILAZU, pa bi se zakljucanom zapisu i dalje smjelo podmetnuti sto se
     * hoce. Provjera stoji prije provjere stupca - zakljucan zapis se odbija bez obzira na
     * to je li ostatak poziva uopce smislen.
     */
    @Test
    @DisplayName("zakljucanom zapisu se ne smije priloziti datoteka")
    void rejectsUploadOnLockedSurvey() {
        templateOwned();
        lockedSurvey();

        assertThatThrownBy(() -> service.upload(TEMPLATE_ID, SURVEY_ID, "ponuda", file("a.pdf", "x")))
                .isInstanceOf(RecordLockedException.class)
                .hasMessageContaining("ana");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("sa zakljucanog zapisa se ne smije maknuti prilog")
    void rejectsDeleteOnLockedSurvey() {
        templateOwned();
        lockedSurvey();
        Attachment attachment = new Attachment(3L, TEMPLATE_ID, SURVEY_ID, "ponuda", "ponuda.pdf", null, 10, "ana");
        attachment.setId(77L);
        when(repository.findById(77L)).thenReturn(Optional.of(attachment));

        assertThatThrownBy(() -> service.delete(TEMPLATE_ID, 77L)).isInstanceOf(RecordLockedException.class);

        verify(repository, never()).delete(any());
        verify(contentRepository, never()).deleteRowsWithoutUnlinking(any());
    }

    /**
     * Bez ove provjere bi se datoteka mogla objesiti na bilo koji naziv stupca - i na onaj
     * koji ne postoji - pa bi u bazi zavrsili prilozi koje nijedan ekran ne prikazuje.
     */
    @Test
    @DisplayName("prilaganje na stupac koji nije za datoteke se odbija")
    void rejectsUploadOnNonFileColumn() {
        templateOwned();
        surveyBelongsTo(TEMPLATE_ID);
        schemaHas(new ColumnDefinitionResponse("naziv", "string", null));

        assertThatThrownBy(() -> service.upload(TEMPLATE_ID, SURVEY_ID, "naziv", file("a.pdf", "x")))
                .isInstanceOf(InvalidAttachmentException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("prilaganje na nepostojeci stupac je 404")
    void rejectsUploadOnUnknownColumn() {
        templateOwned();
        surveyBelongsTo(TEMPLATE_ID);
        schemaHas(new ColumnDefinitionResponse("naziv", "string", null));

        assertThatThrownBy(() -> service.upload(TEMPLATE_ID, SURVEY_ID, "nepostojeci", file("a.pdf", "x")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** Zapis tudeg obrasca se ne smije ni otkriti - kao i svugdje drugdje, 404. */
    @Test
    @DisplayName("prilaganje uz zapis drugog obrasca je 404")
    void rejectsUploadForForeignSurvey() {
        templateOwned();
        surveyBelongsTo(99L);

        assertThatThrownBy(() -> service.upload(TEMPLATE_ID, SURVEY_ID, "ponuda", file("a.pdf", "x")))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("prazna datoteka se odbija")
    void rejectsEmptyFile() {
        templateOwned();
        surveyBelongsTo(TEMPLATE_ID);
        schemaHas(fileColumn("ponuda"));

        assertThatThrownBy(() -> service.upload(TEMPLATE_ID, SURVEY_ID, "ponuda", file("prazno.pdf", "")))
                .isInstanceOf(InvalidAttachmentException.class);
    }

    /**
     * Springova granica odbija zahtjev prije nego stigne do koda, ali je postavka posluzitelja
     * i vrijedi za svaki multipart zahtjev. Ova je pravilo ove znacajke - da promjena te
     * postavke tiho ne promijeni sto aplikacija prihvaca.
     */
    @Test
    @DisplayName("prevelika datoteka se odbija i kad je Spring pusti")
    void rejectsTooLargeFile() {
        templateOwned();
        surveyBelongsTo(TEMPLATE_ID);
        schemaHas(fileColumn("ponuda"));

        String tooBig = "x".repeat(1024 * 1024 + 1);

        assertThatThrownBy(() -> service.upload(TEMPLATE_ID, SURVEY_ID, "ponuda", file("veliko.pdf", tooBig)))
                .isInstanceOf(InvalidAttachmentException.class)
                .hasMessageContaining("prevelika");

        verify(repository, never()).save(any());
    }

    /**
     * Neki klijenti posalju cijelu putanju umjesto imena. Ona u nasem imenu nema sto raditi, a
     * i zavrsi u zaglavlju pri preuzimanju - gdje ne smije nagovarati tude racunalo gdje da
     * datoteku spremi.
     */
    @Test
    @DisplayName("putanja se mice iz imena datoteke")
    void stripsPathFromFileName() {
        templateOwned();
        surveyBelongsTo(TEMPLATE_ID);
        schemaHas(fileColumn("ponuda"));
        saveReturnsWithId();

        service.upload(TEMPLATE_ID, SURVEY_ID, "ponuda", file("C:\\Users\\ana\\Desktop\\ponuda.pdf", "x"));

        assertThat(savedAttachment().getFileName()).isEqualTo("ponuda.pdf");
    }

    @Test
    @DisplayName("ime bez ijednog upotrebljivog znaka dobiva zamjenu")
    void fallsBackWhenFileNameIsUnusable() {
        templateOwned();
        surveyBelongsTo(TEMPLATE_ID);
        schemaHas(fileColumn("ponuda"));
        saveReturnsWithId();

        service.upload(TEMPLATE_ID, SURVEY_ID, "ponuda", file(null, "x"));

        assertThat(savedAttachment().getFileName()).isEqualTo("datoteka");
    }

    // ===================== DOHVAT I BRISANJE =====================

    @Test
    @DisplayName("prilog drugog obrasca se ne moze ni preuzeti ni obrisati")
    void hidesAttachmentOfAnotherTemplate() {
        templateOwned();
        Attachment foreign = new Attachment(3L, 99L, SURVEY_ID, "ponuda", "tudje.pdf", null, 10, "netko");
        foreign.setId(77L);
        when(repository.findById(77L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.download(TEMPLATE_ID, 77L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(TEMPLATE_ID, 77L)).isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("brisanje priloga odnosi i njegov sadrzaj")
    void deleteRemovesContentToo() {
        templateOwned();
        // brisanje priloga od 12.08. gleda i zapis - sa zakljucanog se dokaz ne smije maknuti
        surveyBelongsTo(TEMPLATE_ID);
        Attachment attachment = new Attachment(3L, TEMPLATE_ID, SURVEY_ID, "ponuda", "ponuda.pdf", null, 10, "ana");
        attachment.setId(77L);
        when(repository.findById(77L)).thenReturn(Optional.of(attachment));

        service.delete(TEMPLATE_ID, 77L);

        // Kroz removeContent, koji radi lo_unlink pa tek onda brise redak. Ovaj je test do sada
        // trazio samo brisanje RETKA - i uredno prolazio dok je svaki obrisan prilog ostavljao
        // svoju datoteku u pg_largeobject zauvijek. Mock je potvrdivao poziv, a ne posljedicu;
        // posljedicu provjerava AttachmentStorageTest, uz pravu bazu.
        verify(contentRepository).removeContent(List.of(77L));
        verify(repository).delete(attachment);
    }

    @Test
    @DisplayName("popis priloga obrasca ide jednim upitom, bez sadrzaja datoteka")
    void listsTemplateAttachments() {
        templateOwned();
        Attachment attachment = new Attachment(3L, TEMPLATE_ID, SURVEY_ID, "ponuda", "ponuda.pdf", null, 10, "ana");
        attachment.setId(77L);
        when(repository.findByTemplateIdOrderByUploadedAtAsc(TEMPLATE_ID)).thenReturn(List.of(attachment));

        List<AttachmentResponse> responses = service.listForTemplate(TEMPLATE_ID);

        assertThat(responses).singleElement()
                .satisfies(response -> assertThat(response.fileName()).isEqualTo("ponuda.pdf"));
        // sadrzaj se ne dira - on je u drugoj tablici i ovdje se ne smije ni dohvatiti
        verify(contentRepository, never()).findById(any());
    }

    @Test
    @DisplayName("tudi obrazac se odbija prije nego se dodirne ijedan prilog")
    void refusesForeignTemplateBeforeTouchingAttachments() {
        when(templateService.requireOwned(TEMPLATE_ID))
                .thenThrow(new ResourceNotFoundException("Template", TEMPLATE_ID));

        assertThatCode(() -> service.listForTemplate(TEMPLATE_ID))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(repository, never()).findByTemplateIdOrderByUploadedAtAsc(any());
    }
}
