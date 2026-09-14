package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.SurveyResponse;
import com.example.demo.dto.UpdateSurveyRequest;
import com.example.demo.exception.ForbiddenException;
import com.example.demo.exception.RecordLockedException;
import com.example.demo.model.Role;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - zakljucavanje zapisa.
 *
 * Pravilo koje se ovdje brani nije "postoji zastavica" nego: zakljucan zapis se NE MIJENJA,
 * a otkljucava ga samo administrator. Zakljucavanje koje bi onaj tko je zakljucao mogao sam
 * poništiti ne bi bilo zastita nego neugodnost, pa je uloga pri otkljucavanju bit znacajke,
 * a ne detalj.
 *
 * Izolacija po firmi se ovdje NE testira ({@link TemplateService} je mockiran) - ona ide kroz
 * isti {@code findOwnedOrThrow} kao i svaki drugi poziv nad zapisom i pokrivena je drugdje.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class RecordLockTest {

    private static final long TEMPLATE_ID = 10L;
    private static final long SURVEY_ID = 1L;
    private static final long COMPANY_ID = 3L;

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

    @Mock
    private LogService logService;

    @Mock
    private AttachmentCleanup attachmentCleanup;

    @Mock
    private ReferenceLookup referenceLookup;

    @Mock
    private RecordChangeService recordChangeService;

    /** Pravi kontekst, a ne mock: ulogu ovdje postavlja svaki test sam i to je bas ono sto se testira. */
    private final AuthContext authContext = new AuthContext();

    private SurveyResultService service;

    @BeforeEach
    void setUp() {
        service = new SurveyResultService(repository, queryRepository, columnDefinitionService, validator, templateService,
                columnSequenceService, codebookItemService, new FormulaEvaluator(), attachmentCleanup,
                recordChangeService, logService, authContext, referenceLookup);

        Template template = new Template(COMPANY_ID, "Obrazac");
        template.setId(TEMPLATE_ID);
        lenient().when(templateService.requireOwned(TEMPLATE_ID)).thenReturn(template);
        lenient().when(repository.save(any(SurveyResult.class))).thenAnswer(call -> call.getArgument(0));

        loggedInAs(Role.USER);
    }

    @AfterEach
    void clearContext() {
        authContext.clear();
    }

    private void loggedInAs(Role role) {
        authContext.set(new AuthenticatedUser(2L, "mhorvat", role, COMPANY_ID));
    }

    /** Zapis koji postoji i pripada obrascu; zakljucan ako je predan tko ga je zakljucao. */
    private SurveyResult existing(String lockedBy) {
        SurveyResult survey = new SurveyResult();
        survey.setId(SURVEY_ID);
        survey.setCompanyId(COMPANY_ID);
        survey.setTemplateId(TEMPLATE_ID);
        survey.setData(new HashMap<>(Map.of("grad", "Split")));
        if (lockedBy != null) {
            survey.setLockedAt(Instant.parse("2026-08-12T09:00:00Z"));
            survey.setLockedBy(lockedBy);
        }
        when(repository.findById(SURVEY_ID)).thenReturn(Optional.of(survey));
        return survey;
    }

    // ===================== ZAKLJUCAVANJE =====================

    @Test
    @DisplayName("zakljucava obican korisnik - to je 'gotov sam', a ne administratorska radnja")
    void plainUserMayLock() {
        existing(null);

        SurveyResponse response = service.lock(TEMPLATE_ID, SURVEY_ID);

        assertThat(response.locked()).isTrue();
        assertThat(response.lockedBy()).isEqualTo("mhorvat");
        assertThat(response.lockedAt()).isNotNull();
        verify(logService).record("RECORD_LOCKED", "Zapis id=1 u obrascu id=10");
    }

    @Test
    @DisplayName("ponovno zakljucavanje ne mijenja tko je zakljucao i ne udvostrucuje dnevnik")
    void lockingTwiceIsIdempotent() {
        existing("ana");

        SurveyResponse response = service.lock(TEMPLATE_ID, SURVEY_ID);

        assertThat(response.lockedBy()).isEqualTo("ana");
        verify(repository, never()).save(any());
        verify(logService, never()).record(any(), any());
    }

    // ===================== STO ZAKLJUCANOST SPRJECAVA =====================

    @Test
    @DisplayName("zakljucan zapis se ne moze izmijeniti - i ne dolazi ni do provjere sheme")
    void lockedRecordRejectsUpdate() {
        existing("ana");

        assertThatThrownBy(() -> service.update(TEMPLATE_ID, SURVEY_ID, new UpdateSurveyRequest(Map.of("grad", "Zadar"))))
                .isInstanceOf(RecordLockedException.class)
                .hasMessageContaining("ana");

        verify(repository, never()).save(any());
        verify(validator, never()).validate(any(), anyList(), any(UniqueValueLookup.class));
        // trag promjena je dio zapisa: odbijena izmjena u njemu nema sto traziti
        verify(recordChangeService, never()).capture(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("zakljucan zapis se ne moze obrisati - ni administrator ga ne brise dok je zakljucan")
    void lockedRecordRejectsDelete() {
        existing("ana");
        loggedInAs(Role.TENANT_ADMIN);

        assertThatThrownBy(() -> service.delete(TEMPLATE_ID, SURVEY_ID))
                .isInstanceOf(RecordLockedException.class);

        verify(repository, never()).deleteById(any());
        verify(attachmentCleanup, never()).forSurvey(any());
    }

    @Test
    @DisplayName("otkljucan zapis se i dalje mijenja normalno")
    void unlockedRecordStillUpdates() {
        existing(null);

        assertThatCode(() -> service.update(TEMPLATE_ID, SURVEY_ID, new UpdateSurveyRequest(Map.of("grad", "Zadar"))))
                .doesNotThrowAnyException();

        verify(repository).save(any());
    }

    // ===================== OTKLJUCAVANJE =====================

    @Test
    @DisplayName("obican korisnik ne moze otkljucati - inace bi zakljucavanje bilo samo neugodnost")
    void plainUserMayNotUnlock() {
        existing("mhorvat");

        assertThatThrownBy(() -> service.unlock(TEMPLATE_ID, SURVEY_ID))
                .isInstanceOf(ForbiddenException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("administrator firme otkljucava zapis svoje firme")
    void tenantAdminMayUnlock() {
        existing("mhorvat");
        loggedInAs(Role.TENANT_ADMIN);

        SurveyResponse response = service.unlock(TEMPLATE_ID, SURVEY_ID);

        assertThat(response.locked()).isFalse();
        assertThat(response.lockedAt()).isNull();
        assertThat(response.lockedBy()).isNull();
        // dnevnik pamti tko ga je bio zakljucao - inace se nakon otkljucavanja vise ne zna
        verify(logService).record("RECORD_UNLOCKED", "Zapis id=1 u obrascu id=10 (zaključao mhorvat)");
    }

    @Test
    @DisplayName("globalni administrator takoder otkljucava")
    void globalAdminMayUnlock() {
        existing("mhorvat");
        authContext.set(new AuthenticatedUser(1L, "admin", Role.ADMIN, null));

        assertThat(service.unlock(TEMPLATE_ID, SURVEY_ID).locked()).isFalse();
    }

    @Test
    @DisplayName("otkljucavanje vec otkljucanog zapisa ne mijenja nista i ne ostavlja trag")
    void unlockingUnlockedRecordChangesNothing() {
        existing(null);
        loggedInAs(Role.TENANT_ADMIN);

        assertThat(service.unlock(TEMPLATE_ID, SURVEY_ID).locked()).isFalse();

        verify(repository, never()).save(any());
        verify(logService, never()).record(any(), any());
    }
}
