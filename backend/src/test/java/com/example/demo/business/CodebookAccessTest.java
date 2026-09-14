package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.CreateCodebookRequest;
import com.example.demo.dto.UpdateCodebookRequest;
import com.example.demo.exception.ForbiddenException;
import com.example.demo.exception.InvalidCodebookException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookScope;
import com.example.demo.model.Role;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.CodebookRepository;
import com.example.demo.service.CodebookService;
import com.example.demo.service.CodebookUsage;
import com.example.demo.service.LogService;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - tko sto smije sa sifrarnicima.
 *
 * Sifrarnik je konfiguracija, a ne podatak: obican korisnik ga cita, ne mijenja. Uz to
 * globalni sifrarnik nije "sifrarnik s vecim ovlastima" nego sifrarnik koji ne pripada
 * nikome - pa ga smije mijenjati samo onaj tko nije vezan ni uz jednu firmu.
 *
 * Dvije vrste odbijanja, i razlika je namjerna:
 *   403 - smije ga vidjeti, ali ne i mijenjati,
 *   404 - nije mu ni vidljiv (tuda firma); ne smije doznati ni da postoji.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CodebookAccessTest {

    private static final Long OWN_COMPANY = 7L;
    private static final Long OTHER_COMPANY = 8L;

    @Mock
    private CodebookRepository repository;

    @Mock
    private CodebookItemRepository itemRepository;

    @Mock
    private LogService logService;

    @Mock
    private CodebookUsage codebookUsage;

    // pravi konteksti - provjerava se bas pravilo koje iz njih izlazi
    private final TenantContext tenantContext = new TenantContext();
    private final AuthContext authContext = new AuthContext();

    @AfterEach
    void clearContexts() {
        tenantContext.clear();
        authContext.clear();
    }

    private CodebookService service() {
        return new CodebookService(repository, itemRepository, codebookUsage, tenantContext, authContext, logService);
    }

    private void loggedInAs(Role role, Long companyId) {
        authContext.set(new AuthenticatedUser(1L, role.name().toLowerCase(), role, companyId));
        if (companyId != null) {
            tenantContext.set(companyId);
        }
    }

    private Codebook stored(Long id, CodebookScope scope, Long companyId) {
        Codebook codebook = new Codebook("Statusi", scope, companyId);
        codebook.setId(id);
        when(repository.findById(id)).thenReturn(Optional.of(codebook));
        return codebook;
    }

    // ===================== CITANJE =====================

    @Test
    @DisplayName("obican korisnik smije citati globalni sifrarnik")
    void userCanReadGlobal() {
        Codebook global = stored(1L, CodebookScope.GLOBAL, null);
        loggedInAs(Role.USER, OWN_COMPANY);

        assertThat(service().requireAccessible(1L)).isSameAs(global);
    }

    @Test
    @DisplayName("obican korisnik smije citati sifrarnik svoje firme")
    void userCanReadOwnCompanyCodebook() {
        Codebook own = stored(1L, CodebookScope.TENANT, OWN_COMPANY);
        loggedInAs(Role.USER, OWN_COMPANY);

        assertThat(service().requireAccessible(1L)).isSameAs(own);
    }

    @Test
    @DisplayName("tudi sifrarnik je 404, a ne 403 - ne smije doznati ni da postoji")
    void otherCompanyCodebookLooksMissing() {
        stored(1L, CodebookScope.TENANT, OTHER_COMPANY);
        loggedInAs(Role.USER, OWN_COMPANY);

        assertThatThrownBy(() -> service().requireAccessible(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("administrator firme ne vidi sifrarnik druge firme - sire ovlasti ne znace siri doseg")
    void tenantAdminCannotReachOtherCompany() {
        stored(1L, CodebookScope.TENANT, OTHER_COMPANY);
        loggedInAs(Role.TENANT_ADMIN, OWN_COMPANY);

        assertThatThrownBy(() -> service().requireAccessible(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ===================== PISANJE =====================

    @Test
    @DisplayName("obican korisnik ne smije stvoriti, preimenovati ni obrisati sifrarnik")
    void userCannotWrite() {
        stored(1L, CodebookScope.TENANT, OWN_COMPANY);
        loggedInAs(Role.USER, OWN_COMPANY);
        CodebookService service = service();

        assertThatThrownBy(() -> service.create(new CreateCodebookRequest("Novi", CodebookScope.TENANT)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.rename(1L, new UpdateCodebookRequest("Drugo ime")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(ForbiddenException.class);

        verify(repository, never()).save(any());
        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("administrator firme smije uredivati sifrarnik SVOJE firme")
    void tenantAdminCanEditOwnCompanyCodebook() {
        Codebook own = stored(1L, CodebookScope.TENANT, OWN_COMPANY);
        loggedInAs(Role.TENANT_ADMIN, OWN_COMPANY);

        assertThat(service().requireEditable(1L)).isSameAs(own);
    }

    @Test
    @DisplayName("administrator firme NE smije mijenjati globalni sifrarnik - vidi ga, ali ne dira")
    void tenantAdminCannotEditGlobal() {
        stored(1L, CodebookScope.GLOBAL, null);
        loggedInAs(Role.TENANT_ADMIN, OWN_COMPANY);
        CodebookService service = service();

        // vidjeti ga smije...
        assertThat(service.requireAccessible(1L)).isNotNull();
        // ...ali mijenjati ne, i to je 403, ne 404
        assertThatThrownBy(() -> service.requireEditable(1L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("administrator firme ne moze stvoriti globalni sifrarnik")
    void tenantAdminCannotCreateGlobal() {
        loggedInAs(Role.TENANT_ADMIN, OWN_COMPANY);

        assertThatThrownBy(() -> service().create(new CreateCodebookRequest("Države", CodebookScope.GLOBAL)))
                .isInstanceOf(ForbiddenException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("globalni administrator smije mijenjati globalni sifrarnik")
    void adminCanEditGlobal() {
        Codebook global = stored(1L, CodebookScope.GLOBAL, null);
        loggedInAs(Role.ADMIN, null);

        assertThat(service().requireEditable(1L)).isSameAs(global);
    }

    // ===================== BEZ ODABRANE FIRME =====================

    @Test
    @DisplayName("globalni administrator bez odabrane firme vidi samo globalne sifrarnike")
    void adminWithoutCompanySeesOnlyGlobal() {
        loggedInAs(Role.ADMIN, null);
        when(repository.findVisible(null, "%")).thenReturn(List.of());

        service().list(null, null);

        // upit ide s praznom firmom; TENANT sifrarnici tako ispadaju sami
        verify(repository).findVisible(null, "%");
    }

    @Test
    @DisplayName("globalni administrator bez odabrane firme ne moze stvoriti sifrarnik firme")
    void adminWithoutCompanyCannotCreateTenantCodebook() {
        loggedInAs(Role.ADMIN, null);

        assertThatThrownBy(() -> service().create(new CreateCodebookRequest("Statusi", CodebookScope.TENANT)))
                .isInstanceOf(InvalidCodebookException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("globalni administrator bez odabrane firme ne vidi nicije firmine sifrarnike")
    void adminWithoutCompanyCannotReachTenantCodebook() {
        stored(1L, CodebookScope.TENANT, OWN_COMPANY);
        loggedInAs(Role.ADMIN, null);

        assertThatThrownBy(() -> service().requireAccessible(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
