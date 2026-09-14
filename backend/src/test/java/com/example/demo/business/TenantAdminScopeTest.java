package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.CompanyResponse;
import com.example.demo.dto.CreateCompanyRequest;
import com.example.demo.dto.CreateUserRequest;
import com.example.demo.dto.UserResponse;
import com.example.demo.exception.ForbiddenException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Company;
import com.example.demo.model.Role;
import com.example.demo.model.User;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.service.CompanyPurgeService;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.CompanyService;
import com.example.demo.service.LogService;
import com.example.demo.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - doseg administratora firme (TENANT_ADMIN).
 *
 * Ta uloga je opasnija od ostalih jer je jedina koja ima sire ovlasti, ali NE i siri
 * doseg. Sve ovdje provjerava jednu istu granicu: on radi nad korisnicima, ali samo
 * unutar SVOJE firme, i nikad ne moze doci do ovlasti vece od vlastite.
 *
 * Dvije vrste odbijanja, i razlika je namjerna:
 *   - {@link ForbiddenException} (403) kad je radnja sama po sebi zabranjena
 *     (stvaranje globalnog administratora, diranje firmi),
 *   - {@link ResourceNotFoundException} (404) kad radnja cilja tudi racun - njemu se
 *     ne smije potvrditi ni da taj racun postoji.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantAdminScopeTest {

    private static final Long OWN_COMPANY = 7L;
    private static final Long OTHER_COMPANY = 8L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private LogService logService;

    @Mock
    private CompanyPurgeService purgeService;

    // pravi kontekst - provjerava se bas pravilo koje iz njega izlazi
    private final AuthContext authContext = new AuthContext();

    @BeforeEach
    void loggedInAsTenantAdmin() {
        authContext.set(new AuthenticatedUser(5L, "sef", Role.TENANT_ADMIN, OWN_COMPANY));
        when(passwordEncoder.encode(any())).thenReturn("$2a$hash");
        when(userRepository.save(any())).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });
    }

    @AfterEach
    void clearContext() {
        authContext.clear();
    }

    private UserService userService() {
        return new UserService(userRepository, companyRepository, passwordEncoder, authContext, logService);
    }

    private CompanyService companyService() {
        return new CompanyService(companyRepository, authContext, logService, purgeService);
    }

    private static User user(Long id, String username, Role role, Long companyId) {
        User user = new User(username, "$2a$hash", role, companyId);
        user.setId(id);
        return user;
    }

    // ===================== STO SMIJE =====================

    @Test
    @DisplayName("dodaje korisnika u svoju firmu i kad companyId uopce ne posalje")
    void createsIntoOwnCompanyWithoutSendingIt() {
        Company own = new Company("Acme");
        own.setId(OWN_COMPANY);
        when(companyRepository.findById(OWN_COMPANY)).thenReturn(Optional.of(own));

        // forma administratora firme nema birac firme, pa salje null
        UserResponse created = userService().create(new CreateUserRequest("ana", "tajna123", Role.USER, null));

        assertThat(created.companyId()).isEqualTo(OWN_COMPANY);
        assertThat(created.companyName()).isEqualTo("Acme");
    }

    @Test
    @DisplayName("smije imenovati drugog administratora SVOJE firme - moze delegirati")
    void createsAnotherTenantAdminInOwnCompany() {
        Company own = new Company("Acme");
        own.setId(OWN_COMPANY);
        when(companyRepository.findById(OWN_COMPANY)).thenReturn(Optional.of(own));

        UserResponse created = userService()
                .create(new CreateUserRequest("zamjenik", "tajna123", Role.TENANT_ADMIN, OWN_COMPANY));

        assertThat(created.role()).isEqualTo(Role.TENANT_ADMIN);
        assertThat(created.companyId()).isEqualTo(OWN_COMPANY);
    }

    @Test
    @DisplayName("brise korisnika svoje firme")
    void deletesUserFromOwnCompany() {
        User own = user(11L, "ana", Role.USER, OWN_COMPANY);
        when(userRepository.findById(11L)).thenReturn(Optional.of(own));

        userService().delete(11L);

        verify(userRepository).delete(own);
    }

    // ===================== STO NE SMIJE =====================

    @Test
    @DisplayName("u popisu korisnika vidi samo svoju firmu - popis svih se ni ne dohvaca")
    void seesOnlyOwnCompanyUsers() {
        Company own = new Company("Acme");
        own.setId(OWN_COMPANY);
        when(companyRepository.findAll()).thenReturn(List.of(own));
        when(userRepository.findByCompanyId(OWN_COMPANY))
                .thenReturn(List.of(user(11L, "ana", Role.USER, OWN_COMPANY)));

        List<UserResponse> users = userService().getAll();

        assertThat(users).extracting(UserResponse::username).containsExactly("ana");
        // globalni administratori nemaju companyId, pa ovim upitom ni ne mogu izaci
        verify(userRepository, never()).findAll();
    }

    @Test
    @DisplayName("ne moze stvoriti globalnog administratora - to bi bilo penjanje iznad vlastite uloge")
    void cannotCreateGlobalAdmin() {
        assertThatThrownBy(() -> userService().create(new CreateUserRequest("novi", "tajna123", Role.ADMIN, null)))
                .isInstanceOf(ForbiddenException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("ne moze dodati korisnika u tudu firmu ni kad je izricito posalje u tijelu")
    void cannotCreateIntoOtherCompany() {
        assertThatThrownBy(() ->
                userService().create(new CreateUserRequest("ana", "tajna123", Role.USER, OTHER_COMPANY)))
                .isInstanceOf(ForbiddenException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("tudi korisnik mu je 404, a ne 403 - ne smije doznati ni da postoji")
    void otherCompanyUserLooksMissing() {
        when(userRepository.findById(22L)).thenReturn(Optional.of(user(22L, "tudi", Role.USER, OTHER_COMPANY)));

        assertThatThrownBy(() -> userService().delete(22L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("globalni administrator mu je jednako nevidljiv - on firmu nema, pa nije u dosegu")
    void cannotDeleteGlobalAdmin() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "admin", Role.ADMIN, null)));

        assertThatThrownBy(() -> userService().delete(1L))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(userRepository, never()).delete(any());
    }

    @Test
    @DisplayName("ne smije stvarati, preimenovati ni brisati firme - to ostaje globalnom administratoru")
    void cannotTouchCompanies() {
        CompanyService service = companyService();

        assertThatThrownBy(() -> service.create(new CreateCompanyRequest("Nova")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.update(OWN_COMPANY, new CreateCompanyRequest("Drugo ime")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.delete(OWN_COMPANY))
                .isInstanceOf(ForbiddenException.class);

        verify(companyRepository, never()).save(any());
        verify(companyRepository, never()).delete(any());
    }

    @Test
    @DisplayName("u popisu firmi vidi samo svoju, jednako kao obican korisnik")
    void seesOnlyOwnCompanyInList() {
        Company own = new Company("Acme");
        own.setId(OWN_COMPANY);
        when(companyRepository.findByIdAndDeletedAtIsNull(OWN_COMPANY)).thenReturn(Optional.of(own));

        List<CompanyResponse> companies = companyService().getAll();

        assertThat(companies).extracting(CompanyResponse::id).containsExactly(OWN_COMPANY);
        verify(companyRepository, never()).findByDeletedAtIsNull();
    }

    @Test
    @DisplayName("ne smije ni vidjeti arhivu ni trajno isprazniti firmu - ni vlastitu")
    void cannotTouchArchive() {
        CompanyService service = companyService();

        assertThatThrownBy(service::getArchived).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.restore(OWN_COMPANY)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.purge(OWN_COMPANY)).isInstanceOf(ForbiddenException.class);

        verify(purgeService, never()).purge(any());
    }
}
