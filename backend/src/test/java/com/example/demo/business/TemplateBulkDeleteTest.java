package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.DeletePlanResponse;
import com.example.demo.exception.RecordReferencedException;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.ColumnOptions;
import com.example.demo.model.Role;
import com.example.demo.model.Template;
import com.example.demo.repository.AttachmentRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.repository.TemplateRepository;
import com.example.demo.service.AttachmentCleanup;
import com.example.demo.service.LogService;
import com.example.demo.service.RecordChangeService;
import com.example.demo.service.ReferenceLookup;
import com.example.demo.service.TemplateService;
import com.example.demo.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * BUSINESS test - skupno brisanje obrazaca.
 *
 * Cijela razlika prema pojedinacnom brisanju je u jednoj recenici: veza izmedu dva obrasca
 * koji OBA nestaju nije prepreka. Pojedinacno se takav par ne da obrisati nijednim
 * redoslijedom - svaki drzi onoga drugog - pa se prije ovoga morao rucno obrisati stupac s
 * vezom. Zato se ovdje najvise testira upravo ta granica: sto je "iznutra", a sto "izvana".
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class TemplateBulkDeleteTest {

    private static final long COMPANY = 3L;

    @Mock
    private TemplateRepository repository;
    @Mock
    private SurveyResultRepository surveyRepository;
    @Mock
    private AttachmentRepository attachmentRepository;
    @Mock
    private TenantContext tenantContext;
    @Mock
    private AuthContext authContext;
    @Mock
    private AttachmentCleanup attachmentCleanup;
    @Mock
    private RecordChangeService recordChangeService;
    @Mock
    private LogService logService;

    private TemplateService service;
    private ReferenceLookup referenceLookup;

    @BeforeEach
    void setUp() {
        // ReferenceLookup nije mock: upravo je njegovo pravilo ono sto se ovdje testira
        referenceLookup = new ReferenceLookup(repository, surveyRepository, null, null, tenantContext);
        service = new TemplateService(repository, surveyRepository, tenantContext,
                authContext, attachmentRepository, attachmentCleanup, recordChangeService, logService,
                referenceLookup);
        lenient().when(tenantContext.require()).thenReturn(COMPANY);
        lenient().when(authContext.require())
                .thenReturn(new AuthenticatedUser(1L, "admin", Role.ADMIN, COMPANY));
    }

    private Template template(long id, String name, ColumnEntry... columns) {
        Template t = new Template(COMPANY, name);
        t.setId(id);
        t.setDefinitions(List.of(columns));
        return t;
    }

    private ColumnEntry reference(String key, long targetTemplateId) {
        return new ColumnEntry(key, "reference", null, key, false, false, null, false, null,
                ColumnOptions.EMPTY.withTargetTemplateId(targetTemplateId));
    }

    /** Obrasci koje "firma ima" - iz njih ReferenceLookup racuna tko na koga pokazuje. */
    private void companyHas(Template... templates) {
        when(repository.findByCompanyId(COMPANY)).thenReturn(List.of(templates));
        for (Template t : templates) {
            lenient().when(repository.findById(t.getId())).thenReturn(Optional.of(t));
        }
    }

    // ===================== granica odabira =====================

    /**
     * Ovo je kvar zbog kojeg skupno brisanje i postoji: "Rizici" pokazuju na "Odgovorne
     * osobe", pa se pojedinacno ne da obrisati ni jedan ni drugi.
     */
    @Test
    @DisplayName("par koji pokazuje jedan na drugoga briše se kad idu zajedno")
    void deletesMutuallyReferencingPairTogether() {
        Template osobe = template(20L, "Odgovorne osobe");
        Template rizici = template(21L, "Rizici", reference("odgovorna", 20L));
        companyHas(osobe, rizici);

        assertThatCode(() -> service.deleteAll(List.of(20L, 21L))).doesNotThrowAnyException();

        verify(repository).delete(osobe);
        verify(repository).delete(rizici);
    }

    @Test
    @DisplayName("veza iz obrasca koji ostaje i dalje brani brisanje")
    void refusesWhenReferenceComesFromOutside() {
        Template osobe = template(20L, "Odgovorne osobe");
        Template rizici = template(21L, "Rizici", reference("odgovorna", 20L));
        companyHas(osobe, rizici);

        // brise se samo cilj; "Rizici" ostaju i njihov bi stupac ostao pokazivati u prazno
        assertThatThrownBy(() -> service.deleteAll(List.of(20L)))
                .isInstanceOf(RecordReferencedException.class)
                .hasMessageContaining("Rizici")
                .hasMessageContaining("odgovorna");

        verify(repository, never()).delete(any());
    }

    /** Prepreka mora imenovati i obrazac i stupac - inace se ne zna sto tocno maknuti. */
    @Test
    @DisplayName("najava imenuje prepreku umjesto da samo odbije")
    void planNamesTheBlocker() {
        Template osobe = template(20L, "Odgovorne osobe");
        Template rizici = template(21L, "Rizici", reference("odgovorna", 20L));
        companyHas(osobe, rizici);

        DeletePlanResponse plan = service.deletionPlan(List.of(20L));

        assertThat(plan.deletable()).isFalse();
        assertThat(plan.blockers()).singleElement().satisfies(b -> {
            assertThat(b).contains("Rizici");
            assertThat(b).contains("odgovorna");
            assertThat(b).contains("Odgovorne osobe");
        });
    }

    @Test
    @DisplayName("kad su oba u odabiru, najava nema prepreka")
    void planHasNoBlockersWhenBothSelected() {
        companyHas(template(20L, "Odgovorne osobe"), template(21L, "Rizici", reference("odgovorna", 20L)));

        DeletePlanResponse plan = service.deletionPlan(List.of(20L, 21L));

        assertThat(plan.deletable()).isTrue();
        assertThat(plan.blockers()).isEmpty();
    }

    // ===================== najava =====================

    /**
     * Brisanje odnosi i zapise i priloge, nepovratno. "3 obrasca" i "3 obrasca i 1.240 zapisa"
     * nisu ista odluka, a poslije se to nema gdje vidjeti.
     */
    @Test
    @DisplayName("najava broji zapise i priloge koji odlaze s obrascem")
    void planCountsRecordsAndAttachments() {
        companyHas(template(20L, "Odgovorne osobe"));
        when(surveyRepository.countByTemplateId(20L)).thenReturn(1240L);
        when(attachmentRepository.countByTemplateId(20L)).thenReturn(7L);

        DeletePlanResponse plan = service.deletionPlan(List.of(20L));

        assertThat(plan.templates()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("Odgovorne osobe");
            assertThat(t.recordCount()).isEqualTo(1240L);
            assertThat(t.attachmentCount()).isEqualTo(7L);
        });
    }

    @Test
    @DisplayName("najava ne briše ništa")
    void planDeletesNothing() {
        companyHas(template(20L, "Odgovorne osobe"));

        service.deletionPlan(List.of(20L));

        verify(repository, never()).delete(any());
        verify(surveyRepository, never()).deleteAll(any());
        verify(logService, never()).record(any(), any());
    }

    // ===================== ovlasti i doseg =====================

    /** Isti cuvar kao kod pojedinacnog brisanja - skupno ga ne smije zaobici. */
    @Test
    @DisplayName("obrazac druge firme se ne da obrisati ni u skupini")
    void refusesTemplateOfAnotherCompany() {
        Template foreign = new Template(99L, "Tudi");
        foreign.setId(30L);
        when(repository.findById(30L)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.deleteAll(List.of(30L)))
                .isInstanceOf(com.example.demo.exception.ResourceNotFoundException.class);

        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("skupno brisanje ostavlja trag u dnevniku")
    void recordsInAuditLog() {
        companyHas(template(20L, "Odgovorne osobe"), template(21L, "Rizici"));

        service.deleteAll(List.of(20L, 21L));

        verify(logService).record(org.mockito.ArgumentMatchers.eq("TEMPLATES_DELETED"),
                org.mockito.ArgumentMatchers.contains("Odgovorne osobe"));
    }
}
