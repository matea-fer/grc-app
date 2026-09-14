package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.CreateTemplateRequest;
import com.example.demo.dto.UpdateTemplateRequest;
import com.example.demo.exception.ForbiddenException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Role;
import com.example.demo.model.Template;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.repository.TemplateRepository;
import com.example.demo.service.ReferenceLookup;
import com.example.demo.service.AttachmentCleanup;
import com.example.demo.service.LogService;
import com.example.demo.service.RecordChangeService;
import com.example.demo.service.TemplateService;
import com.example.demo.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - tko smije mijenjati shemu (obrasce i njihove stupce).
 *
 * Isto pravilo kao kod sifrarnika (CodebookAccessTest): shema je konfiguracija, a ne
 * podatak. Obican korisnik po njoj unosi zapise - i to i dalje smije - ali je ne
 * mijenja. Do 03.08.2026. ovdje nije bilo nikakve provjere uloge, pa je obican
 * korisnik mogao obrisati stupac i time izbrisati vrijednosti iz svih zapisa.
 *
 * Dvije vrste odbijanja, i redoslijed nije kozmetika:
 *   404 - obrazac druge firme; ne smije doznati ni da postoji,
 *   403 - vidi ga, ali ga ne smije mijenjati.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchemaEditingAccessTest {

    private static final Long OWN_COMPANY = 7L;
    private static final Long OTHER_COMPANY = 8L;
    private static final Long TEMPLATE_ID = 1L;

    @Mock
    private TemplateRepository repository;

    // brojanje priloga za najavu skupnog brisanja; ovaj test ga ne dira
    @Mock
    private com.example.demo.repository.AttachmentRepository attachmentRepository;

    @Mock
    private SurveyResultRepository surveyRepository;

    @Mock
    private LogService logService;

    @Mock
    private AttachmentCleanup attachmentCleanup;

    @Mock
    private ReferenceLookup referenceLookup;

    @Mock
    private RecordChangeService recordChangeService;

    // pravi konteksti - provjerava se bas pravilo koje iz njih izlazi
    private final TenantContext tenantContext = new TenantContext();
    private final AuthContext authContext = new AuthContext();

    @AfterEach
    void clearContexts() {
        tenantContext.clear();
        authContext.clear();
    }

    private TemplateService service() {
        return new TemplateService(repository, surveyRepository, tenantContext, authContext, attachmentRepository, attachmentCleanup, recordChangeService, logService, referenceLookup);
    }

    private void loggedInAs(Role role) {
        authContext.set(new AuthenticatedUser(1L, role.name().toLowerCase(), role, OWN_COMPANY));
        tenantContext.set(OWN_COMPANY);
    }

    private Template stored(Long companyId) {
        Template template = new Template(companyId, "Obrazac");
        template.setId(TEMPLATE_ID);
        when(repository.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));
        return template;
    }

    // ===================== CITANJE =====================

    @Test
    @DisplayName("obican korisnik i dalje smije citati shemu - bez nje ne moze unijeti zapis")
    void userCanStillReadSchema() {
        Template own = stored(OWN_COMPANY);
        loggedInAs(Role.USER);

        assertThat(service().requireOwned(TEMPLATE_ID)).isSameAs(own);
    }

    // ===================== PISANJE =====================

    @Test
    @DisplayName("obican korisnik ne smije stvoriti, preimenovati ni obrisati obrazac")
    void userCannotWriteTemplates() {
        stored(OWN_COMPANY);
        loggedInAs(Role.USER);
        TemplateService service = service();

        assertThatThrownBy(() -> service.create(new CreateTemplateRequest("Novi")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.rename(TEMPLATE_ID, new UpdateTemplateRequest("Drugo ime")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.delete(TEMPLATE_ID))
                .isInstanceOf(ForbiddenException.class);

        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
        // brisanje obrasca odnosi i zapise - ni do toga ne smije doci
        verify(surveyRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("obican korisnik ne smije ni do stupaca - requireEditable je isti cuvar")
    void userCannotReachColumnEditing() {
        stored(OWN_COMPANY);
        loggedInAs(Role.USER);

        assertThatThrownBy(() -> service().requireEditable(TEMPLATE_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("administrator firme smije uredivati shemu svoje firme")
    void tenantAdminCanEditOwnCompanySchema() {
        Template own = stored(OWN_COMPANY);
        loggedInAs(Role.TENANT_ADMIN);

        assertThat(service().requireEditable(TEMPLATE_ID)).isSameAs(own);
    }

    @Test
    @DisplayName("globalni administrator smije uredivati shemu odabrane firme")
    void adminCanEditSelectedCompanySchema() {
        Template own = stored(OWN_COMPANY);
        authContext.set(new AuthenticatedUser(1L, "admin", Role.ADMIN, null));
        tenantContext.set(OWN_COMPANY);

        assertThat(service().requireEditable(TEMPLATE_ID)).isSameAs(own);
    }

    @Test
    @DisplayName("tudi obrazac je 404 i administratoru firme - sire ovlasti ne znace siri doseg")
    void tenantAdminCannotReachOtherCompany() {
        stored(OTHER_COMPANY);
        loggedInAs(Role.TENANT_ADMIN);

        assertThatThrownBy(() -> service().requireEditable(TEMPLATE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("obicnom korisniku je tudi obrazac 404, a ne 403 - vlasnistvo se provjerava prvo")
    void ownershipIsCheckedBeforeRole() {
        stored(OTHER_COMPANY);
        loggedInAs(Role.USER);

        // da je redoslijed obrnut, 403 bi odao da taj obrazac postoji
        assertThatThrownBy(() -> service().requireEditable(TEMPLATE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
