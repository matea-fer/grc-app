package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.CompanyResponse;
import com.example.demo.dto.CreateCompanyRequest;
import com.example.demo.dto.CreateUserRequest;
import com.example.demo.exception.ForbiddenException;
import com.example.demo.exception.UnauthorizedException;
import com.example.demo.model.Company;
import com.example.demo.model.Role;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.service.CompanyPurgeService;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.CompanyService;
import com.example.demo.service.LogService;
import com.example.demo.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - sto smije koja uloga.
 *
 * Firme i korisnici nisu ograniceni tenantom nego ULOGOM, pa se ne mogu osloniti na
 * TenantFilter: pravilo mora stajati u servisu. Obicnom korisniku se popis firmi ne
 * odbija, nego SUZAVA na njegovu - iz njega se ispisuje naziv u traci.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class AuthorizationTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private LogService logService;

    @Mock
    private CompanyPurgeService purgeService;

    // pravi kontekst - provjerava se bas pravilo iz njega
    private final AuthContext authContext = new AuthContext();

    @AfterEach
    void clearContext() {
        authContext.clear();
    }

    private CompanyService companyService() {
        return new CompanyService(companyRepository, authContext, logService, purgeService);
    }

    private UserService userService() {
        return new UserService(userRepository, companyRepository, passwordEncoder, authContext, logService);
    }

    private void loggedInAsUser() {
        authContext.set(new AuthenticatedUser(2L, "ana", Role.USER, 7L));
    }

    private void loggedInAsAdmin() {
        authContext.set(new AuthenticatedUser(1L, "admin", Role.ADMIN, null));
    }

    @Test
    @DisplayName("obican korisnik u popisu firmi vidi samo svoju")
    void userSeesOnlyOwnCompany() {
        Company own = new Company("Acme");
        own.setId(7L);
        when(companyRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(own));
        loggedInAsUser();

        List<CompanyResponse> companies = companyService().getAll();

        assertThat(companies).extracting(CompanyResponse::id).containsExactly(7L);
        // popis svih firmi se ni ne dohvaca
        verify(companyRepository, never()).findByDeletedAtIsNull();
    }

    @Test
    @DisplayName("administrator u popisu firmi vidi sve")
    void adminSeesAllCompanies() {
        Company first = new Company("Acme");
        first.setId(7L);
        Company second = new Company("Beta");
        second.setId(8L);
        when(companyRepository.findByDeletedAtIsNull()).thenReturn(List.of(first, second));
        loggedInAsAdmin();

        assertThat(companyService().getAll()).hasSize(2);
    }

    @Test
    @DisplayName("obican korisnik ne smije stvoriti, preimenovati ni arhivirati firmu")
    void userCannotModifyCompanies() {
        loggedInAsUser();
        CompanyService service = companyService();

        assertThatThrownBy(() -> service.create(new CreateCompanyRequest("Nova")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.update(7L, new CreateCompanyRequest("Drugo ime")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.delete(7L))
                .isInstanceOf(ForbiddenException.class);

        verify(companyRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(companyRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("obican korisnik ne smije ni vidjeti arhivu, ni vratiti ni isprazniti firmu")
    void userCannotTouchArchive() {
        loggedInAsUser();
        CompanyService service = companyService();

        assertThatThrownBy(service::getArchived).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.restore(7L)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.purge(7L)).isInstanceOf(ForbiddenException.class);

        // do praznjenja se ne dolazi ni korak: uloga se provjerava prije svega ostalog
        verify(purgeService, never()).purge(org.mockito.ArgumentMatchers.any());
        verify(companyRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("obican korisnik ne smije ni vidjeti ni dodavati korisnike")
    void userCannotTouchUsers() {
        loggedInAsUser();
        UserService service = userService();

        assertThatThrownBy(service::getAll).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.create(new CreateUserRequest("nova", "tajna123", Role.USER, 7L)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.delete(3L)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("neprijavljen zahtjev je 401, prijavljen bez ovlasti 403 - to su razlicite stvari")
    void unauthenticatedIsNotTheSameAsForbidden() {
        // prazan kontekst = nitko nije prijavljen
        assertThatThrownBy(authContext::require).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(authContext::requireAdmin).isInstanceOf(UnauthorizedException.class);

        loggedInAsUser();
        assertThatThrownBy(authContext::requireAdmin).isInstanceOf(ForbiddenException.class);

        loggedInAsAdmin();
        assertThat(authContext.requireAdmin().username()).isEqualTo("admin");
    }
}
