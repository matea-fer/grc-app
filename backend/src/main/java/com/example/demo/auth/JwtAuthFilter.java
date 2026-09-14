package com.example.demo.auth;

import com.example.demo.exception.UnauthorizedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Cita {@code Authorization: Bearer <token>} na pocetku svakog zahtjeva prema
 * {@code /api} i sprema prijavljenog korisnika u {@link AuthContext}.
 *
 * Fail-closed, isto kao {@link com.example.demo.tenant.TenantFilter}: bez valjanog
 * tokena zahtjev se odbija s 401 i NE ide dalje.
 *
 * Mora se izvrsiti PRIJE TenantFilter-a (@Order 1 vs 2), jer TenantFilter iz ovdje
 * postavljenog konteksta cita firmu korisnika. Obrnutim redoslijedom bi tenant bio
 * odreden prije nego se zna tko salje zahtjev.
 *
 * Prijava sama ({@code /api/auth/login}) je jedini izuzetak - da postoji nacin doci
 * do prvog tokena.
 */
@Component
@Order(1)
public class JwtAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
    static final String LOGIN_PATH = "/api/auth/login";

    private final JwtService jwtService;
    private final AuthContext authContext;

    public JwtAuthFilter(JwtService jwtService, AuthContext authContext) {
        this.jwtService = jwtService;
        this.authContext = authContext;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api") || path.equals(LOGIN_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        AuthenticatedUser user;
        try {
            user = jwtService.parse(JwtService.bearerToken(request.getHeader(HttpHeaders.AUTHORIZATION)));
        } catch (UnauthorizedException ex) {
            log.warn("Rejected {} {} - {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
            writeUnauthorized(response);
            return;
        }

        authContext.set(user);
        try {
            chain.doFilter(request, response);
        } finally {
            // Nit se vraca u zajednicki bazen; bez ovoga bi sljedeci zahtjev na njoj naslijedio tudeg korisnika.
            authContext.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        // Isti oblik tijela ({message}) kao kod ostalih gresaka. Filter je prije
        // DispatcherServleta, pa ga @RestControllerAdvice ne hvata i JSON se ispisuje
        // rucno. Poruka je konstantna (bez korisnickog unosa), pa ide doslovno.
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"Prijava je istekla ili nije valjana.\"}");
    }
}
