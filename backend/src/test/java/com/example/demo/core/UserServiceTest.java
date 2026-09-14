package com.example.demo.core;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.CreateUserRequest;
import com.example.demo.dto.UserResponse;
import com.example.demo.exception.DuplicateUsernameException;
import com.example.demo.exception.InvalidUserException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Company;
import com.example.demo.model.Role;
import com.example.demo.model.User;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.LogService;
import com.example.demo.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
 * CORE test - CRUD korisnika. Naglasak je na pravilima koja cuvaju smislen sustav:
 * jedinstveno ime, slaganje uloge i firme, te dvije zastite od zakljucavanja
 * (brisanje sebe i brisanje zadnjeg administratora).
 */
@Tag("core")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    @Mock
    private UserRepository repository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AuthContext authContext;

    @Mock
    private LogService logService;

    @InjectMocks
    private UserService service;

    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, "admin", Role.ADMIN, null);

    private static User user(Long id, String username, Role role, Long companyId) {
        User user = new User(username, "$2a$hash", role, companyId);
        user.setId(id);
        return user;
    }

    @BeforeEach
    void loggedInAsAdmin() {
        when(authContext.requireUserManager()).thenReturn(ADMIN);
        when(passwordEncoder.encode(any())).thenReturn("$2a$hash");
        when(repository.save(any())).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });
    }

    @Test
    @DisplayName("novi korisnik se sprema s hashiranom lozinkom, nikad s cistim tekstom")
    void createStoresHashedPassword() {
        Company company = new Company("Acme");
        company.setId(7L);
        when(companyRepository.existsByIdAndDeletedAtIsNull(7L)).thenReturn(true);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        UserResponse response = service.create(new CreateUserRequest("ana", "tajna123", Role.USER, 7L));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("$2a$hash").isNotEqualTo("tajna123");
        assertThat(response.username()).isEqualTo("ana");
        assertThat(response.companyName()).isEqualTo("Acme");
    }

    @Test
    @DisplayName("zauzeto korisnicko ime se odbija")
    void duplicateUsernameIsRejected() {
        when(repository.existsByUsername("ana")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateUserRequest("ana", "tajna123", Role.USER, 7L)))
                .isInstanceOf(DuplicateUsernameException.class);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("obican korisnik bez firme se odbija - ne bi imao sto vidjeti")
    void userWithoutCompanyIsRejected() {
        assertThatThrownBy(() -> service.create(new CreateUserRequest("ana", "tajna123", Role.USER, null)))
                .isInstanceOf(InvalidUserException.class);
    }

    @Test
    @DisplayName("administrator s firmom se odbija - imao bi dva izvora tenanta")
    void adminWithCompanyIsRejected() {
        assertThatThrownBy(() -> service.create(new CreateUserRequest("sef", "tajna123", Role.ADMIN, 7L)))
                .isInstanceOf(InvalidUserException.class);
    }

    @Test
    @DisplayName("administrator firme bez firme se odbija - kao i obican korisnik, mora joj pripadati")
    void tenantAdminWithoutCompanyIsRejected() {
        assertThatThrownBy(() -> service.create(new CreateUserRequest("sef", "tajna123", Role.TENANT_ADMIN, null)))
                .isInstanceOf(InvalidUserException.class);
    }

    @Test
    @DisplayName("globalni administrator smije stvoriti administratora bilo koje firme")
    void adminCanCreateTenantAdmin() {
        Company company = new Company("Acme");
        company.setId(7L);
        when(companyRepository.existsByIdAndDeletedAtIsNull(7L)).thenReturn(true);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        UserResponse response = service.create(new CreateUserRequest("sef", "tajna123", Role.TENANT_ADMIN, 7L));

        assertThat(response.role()).isEqualTo(Role.TENANT_ADMIN);
        assertThat(response.companyId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("korisnik u nepostojecoj firmi se odbija s 404")
    void unknownCompanyIsRejected() {
        when(companyRepository.existsByIdAndDeletedAtIsNull(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(new CreateUserRequest("ana", "tajna123", Role.USER, 999L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("brisanje vlastitog racuna se odbija - zakljucalo bi sesiju koja ga brise")
    void deletingSelfIsRejected() {
        when(repository.findById(1L)).thenReturn(Optional.of(user(1L, "admin", Role.ADMIN, null)));

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(InvalidUserException.class)
                .hasMessageContaining("vlastiti");

        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("brisanje zadnjeg administratora se odbija - nitko vise ne bi mogao dodavati")
    void deletingLastAdminIsRejected() {
        User onlyAdmin = user(5L, "drugi-admin", Role.ADMIN, null);
        when(repository.findById(5L)).thenReturn(Optional.of(onlyAdmin));
        when(repository.findAll()).thenReturn(List.of(onlyAdmin));

        assertThatThrownBy(() -> service.delete(5L))
                .isInstanceOf(InvalidUserException.class)
                .hasMessageContaining("administrator");
    }

    @Test
    @DisplayName("administrator se smije obrisati dok postoji jos jedan")
    void deletingAdminIsAllowedWhenAnotherRemains() {
        User second = user(5L, "drugi-admin", Role.ADMIN, null);
        when(repository.findById(5L)).thenReturn(Optional.of(second));
        when(repository.findAll()).thenReturn(List.of(user(1L, "admin", Role.ADMIN, null), second));

        service.delete(5L);

        verify(repository).delete(second);
    }
}
