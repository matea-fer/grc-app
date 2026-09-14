package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.auth.JwtAuthFilter;
import com.example.demo.auth.JwtService;
import com.example.demo.model.Role;
import com.example.demo.model.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUSINESS test - ulaz u prijavu. Fail-closed: bez valjanog tokena zahtjev prema
 * /api ne prolazi, a prijavljeni korisnik mora biti u kontekstu za trajanja zahtjeva
 * i nestati nakon njega (da ga ne naslijedi sljedeci zahtjev na istoj niti).
 */
@Tag("business")
class JwtAuthFilterTest {

    private static final String SECRET = "tajna-za-testove-koja-je-dovoljno-duga-32+";

    private final JwtService jwtService = new JwtService(SECRET, 8);
    private final AuthContext authContext = new AuthContext();

    @AfterEach
    void clearContext() {
        authContext.clear();
    }

    private JwtAuthFilter filter() {
        return new JwtAuthFilter(jwtService, authContext);
    }

    private String tokenFor(Role role, Long companyId) {
        User user = new User("ana", "$2a$hash", role, companyId);
        user.setId(42L);
        return jwtService.issue(user);
    }

    /** Lanac koji zabiljezi tko je bio u kontekstu kad je zahtjev "stigao do servisa". */
    private class RecordingChain implements FilterChain {
        boolean called = false;
        AuthenticatedUser userDuringCall;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            called = true;
            userDuringCall = authContext.get();
        }
    }

    private MockHttpServletRequest request(String path, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    @Test
    @DisplayName("valjan token postavi korisnika u kontekst za trajanja zahtjeva, a poslije ga ocisti")
    void validTokenSetsContextDuringRequestAndClearsAfter() throws ServletException, IOException {
        var chain = new RecordingChain();
        var response = new MockHttpServletResponse();

        filter().doFilter(request("/api/templates", "Bearer " + tokenFor(Role.USER, 7L)), response, chain);

        assertThat(chain.called).isTrue();
        assertThat(chain.userDuringCall).isNotNull();
        assertThat(chain.userDuringCall.username()).isEqualTo("ana");
        assertThat(chain.userDuringCall.companyId()).isEqualTo(7L);
        assertThat(authContext.get()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("bez zaglavlja Authorization zahtjev se odbija s 401 i ne ide dalje")
    void missingTokenIsRejected() throws ServletException, IOException {
        var chain = new RecordingChain();
        var response = new MockHttpServletResponse();

        filter().doFilter(request("/api/templates", null), response, chain);

        assertThat(chain.called).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("neispravan token se odbija s 401")
    void invalidTokenIsRejected() throws ServletException, IOException {
        var chain = new RecordingChain();
        var response = new MockHttpServletResponse();

        filter().doFilter(request("/api/templates", "Bearer ovo-nije-token"), response, chain);

        assertThat(chain.called).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("token bez prefiksa Bearer se odbija s 401")
    void tokenWithoutBearerPrefixIsRejected() throws ServletException, IOException {
        var chain = new RecordingChain();
        var response = new MockHttpServletResponse();

        filter().doFilter(request("/api/templates", tokenFor(Role.USER, 7L)), response, chain);

        assertThat(chain.called).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("sama prijava prolazi bez tokena - inace se ne bi imalo kako doci do prvog")
    void loginRouteIsOpen() throws ServletException, IOException {
        var chain = new RecordingChain();
        var response = new MockHttpServletResponse();

        filter().doFilter(request("/api/auth/login", null), response, chain);

        assertThat(chain.called).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
