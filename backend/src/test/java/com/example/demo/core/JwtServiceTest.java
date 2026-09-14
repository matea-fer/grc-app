package com.example.demo.core;

import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.auth.JwtService;
import com.example.demo.exception.UnauthorizedException;
import com.example.demo.model.Role;
import com.example.demo.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CORE test - izdavanje i citanje tokena.
 *
 * Tezina je na tome sto se NE smije procitati: tudi potpis i istekao rok moraju
 * zavrsiti jednako (odbijeno), jer o tome ovisi cijela prijava.
 */
@Tag("core")
class JwtServiceTest {

    private static final String SECRET = "tajna-za-testove-koja-je-dovoljno-duga-32+";
    private static final String OTHER_SECRET = "druga-tajna-za-testove-isto-dovoljno-duga";

    private static User user(Role role, Long companyId) {
        User user = new User("ana", "$2a$hash", role, companyId);
        user.setId(42L);
        return user;
    }

    private static JwtService service() {
        return new JwtService(SECRET, 8);
    }

    @Test
    @DisplayName("izdani token se procita natrag s istim korisnikom, ulogom i firmom")
    void issuedTokenParsesBack() {
        JwtService service = service();

        AuthenticatedUser parsed = service.parse(service.issue(user(Role.USER, 7L)));

        assertThat(parsed.userId()).isEqualTo(42L);
        assertThat(parsed.username()).isEqualTo("ana");
        assertThat(parsed.role()).isEqualTo(Role.USER);
        assertThat(parsed.companyId()).isEqualTo(7L);
        assertThat(parsed.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("ADMIN-ov token nema firmu - nju bira zaglavljem")
    void adminTokenCarriesNoCompany() {
        JwtService service = service();

        AuthenticatedUser parsed = service.parse(service.issue(user(Role.ADMIN, null)));

        assertThat(parsed.companyId()).isNull();
        assertThat(parsed.isAdmin()).isTrue();
    }

    @Test
    @DisplayName("token potpisan drugom tajnom se odbija")
    void tokenSignedWithAnotherSecretIsRejected() {
        String foreign = new JwtService(OTHER_SECRET, 8).issue(user(Role.ADMIN, null));

        assertThatThrownBy(() -> service().parse(foreign))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("istekao token se odbija")
    void expiredTokenIsRejected() {
        // negativan rok -> token kojem je vrijeme isteka vec proslo
        String expired = new JwtService(SECRET, -1).issue(user(Role.USER, 7L));

        assertThatThrownBy(() -> service().parse(expired))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("niz koji uopce nije token se odbija, a ne ruši se")
    void garbageIsRejected() {
        assertThatThrownBy(() -> service().parse("ovo-nije-token"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("nedostajuci token se odbija")
    void missingTokenIsRejected() {
        assertThatThrownBy(() -> service().parse(null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("prekratka tajna obori aplikaciju pri pokretanju, a ne tek na prvoj prijavi")
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtService("prekratko", 8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    @DisplayName("bearerToken izvuce token samo iz ispravnog oblika zaglavlja")
    void bearerTokenReadsHeader() {
        assertThat(JwtService.bearerToken("Bearer abc.def.ghi")).isEqualTo("abc.def.ghi");
        assertThat(JwtService.bearerToken("bearer abc")).isNull();
        assertThat(JwtService.bearerToken("Basic abc")).isNull();
        assertThat(JwtService.bearerToken("Bearer ")).isNull();
        assertThat(JwtService.bearerToken(null)).isNull();
    }
}
