package com.example.demo.core;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.JwtService;
import com.example.demo.dto.LoginRequest;
import com.example.demo.dto.LoginResponse;
import com.example.demo.exception.UnauthorizedException;
import com.example.demo.model.Company;
import com.example.demo.model.Role;
import com.example.demo.model.User;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.AuthService;
import com.example.demo.service.LogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** CORE test - prijava: sto vraca kad uspije i sto radi kad ne uspije. */
@Tag("core")
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private AuthContext authContext;

    @Mock
    private LogService logService;

    @InjectMocks
    private AuthService service;

    private static User user() {
        User user = new User("ana", "$2a$hash", Role.USER, 7L);
        user.setId(42L);
        return user;
    }

    @Test
    @DisplayName("uspjesna prijava vraca token, ulogu i firmu s nazivom")
    void successfulLoginReturnsTokenAndIdentity() {
        Company company = new Company("Acme");
        company.setId(7L);
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(user()));
        when(passwordEncoder.matches("tajna123", "$2a$hash")).thenReturn(true);
        when(jwtService.issue(any())).thenReturn("token-abc");
        when(companyRepository.existsByIdAndDeletedAtIsNull(7L)).thenReturn(true);
        when(companyRepository.findById(7L)).thenReturn(Optional.of(company));

        LoginResponse response = service.login(new LoginRequest("ana", "tajna123"));

        assertThat(response.token()).isEqualTo("token-abc");
        assertThat(response.username()).isEqualTo("ana");
        assertThat(response.role()).isEqualTo(Role.USER);
        assertThat(response.companyId()).isEqualTo(7L);
        assertThat(response.companyName()).isEqualTo("Acme");
        verify(logService).record(eq("LOGIN_SUCCESS"), any(), eq(7L), eq("ana"));
    }

    @Test
    @DisplayName("nepoznato korisnicko ime se odbija i zabiljezi kao neuspjela prijava")
    void unknownUsernameIsRejectedAndLogged() {
        when(userRepository.findByUsername("nitko")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginRequest("nitko", "bilosto")))
                .isInstanceOf(UnauthorizedException.class);

        // nepoznat korisnik nema firmu na koju bi se zapis vezao
        verify(logService).record(eq("LOGIN_FAILED"), any(), eq(null), eq("nitko"));
    }

    @Test
    @DisplayName("kriva lozinka se odbija, a zapis se vezuje uz firmu tog korisnika")
    void wrongPasswordIsRejectedAndLogged() {
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(user()));
        when(passwordEncoder.matches("krivo", "$2a$hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("ana", "krivo")))
                .isInstanceOf(UnauthorizedException.class);

        verify(logService).record(eq("LOGIN_FAILED"), any(), eq(7L), eq("ana"));
    }

    @Test
    @DisplayName("token se ne izdaje kad lozinka ne valja")
    void noTokenIssuedOnFailure() {
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(user()));
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("ana", "krivo")))
                .isInstanceOf(UnauthorizedException.class);

        org.mockito.Mockito.verifyNoInteractions(jwtService);
    }

    @Test
    @DisplayName("ispravna lozinka, ali arhivirana firma - prijava se odbija i token se ne izdaje")
    void archivedCompanyBlocksLoginDespiteCorrectPassword() {
        when(userRepository.findByUsername("ana")).thenReturn(Optional.of(user()));
        when(passwordEncoder.matches("tajna123", "$2a$hash")).thenReturn(true);
        when(companyRepository.existsByIdAndDeletedAtIsNull(7L)).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginRequest("ana", "tajna123")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("arhivirana");

        org.mockito.Mockito.verifyNoInteractions(jwtService);
        verify(logService).record(eq("LOGIN_REJECTED_ARCHIVED"), any(), eq(7L), eq("ana"));
    }
}
