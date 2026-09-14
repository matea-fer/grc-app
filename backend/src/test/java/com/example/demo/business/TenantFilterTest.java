package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.model.Role;
import com.example.demo.repository.CompanyRepository;
import com.example.demo.tenant.TenantContext;
import com.example.demo.tenant.TenantFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - odredivanje firme za zahtjev.
 *
 * Najvaznije pravilo je da TOKEN POBJEDUJE ZAGLAVLJE: tko pripada firmi - a to su i
 * USER i TENANT_ADMIN - dobiva je iz potpisanog tokena, pa mu podmetnuto TenantID
 * zaglavlje ne moze otvoriti tude podatke. Samo globalni ADMIN firmu u tokenu nema i
 * jedini je kome se zaglavlje cita.
 *
 * Uz to vrijedi i dalje fail-closed: ako se firma ne moze odrediti, zahtjev ne prolazi.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class TenantFilterTest {

    @Mock
    private CompanyRepository companyRepository;

    // Pravi kontekst (ne mock) - da se moze provjeriti sto je stvarno u njemu tijekom i nakon lanca.
    private final TenantContext tenantContext = new TenantContext();
    private final AuthContext authContext = new AuthContext();

    @AfterEach
    void clearContexts() {
        tenantContext.clear();
        authContext.clear();
    }

    private TenantFilter filter() {
        return new TenantFilter(companyRepository, tenantContext, authContext);
    }

    private void loggedInAs(Role role, Long companyId) {
        authContext.set(new AuthenticatedUser(1L, role == Role.ADMIN ? "admin" : "ana", role, companyId));
    }

    /** Lanac koji zabiljezi koju je firmu kontekst imao u trenutku kad je zahtjev "stigao do servisa". */
    private static class RecordingChain implements FilterChain {
        private final TenantContext context;
        boolean called = false;
        Long tenantDuringCall;

        RecordingChain(TenantContext context) {
            this.context = context;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            called = true;
            tenantDuringCall = context.get();
        }
    }

    private MockHttpServletRequest request(String path, String tenantHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        if (tenantHeader != null) {
            request.addHeader("TenantID", tenantHeader);
        }
        return request;
    }

    @Test
    @DisplayName("obicnom korisniku firma dolazi iz tokena, a poslije zahtjeva se kontekst ocisti")
    void userGetsCompanyFromToken() throws ServletException, IOException {
        when(companyRepository.existsByIdAndDeletedAtIsNull(7L)).thenReturn(true);
        loggedInAs(Role.USER, 7L);
        var chain = new RecordingChain(tenantContext);
        var response = new MockHttpServletResponse();

        filter().doFilter(request("/api/templates", null), response, chain);

        assertThat(chain.called).isTrue();
        assertThat(chain.tenantDuringCall).isEqualTo(7L);
        // nit se vraca "cista" - inace bi sljedeci zahtjev na njoj naslijedio firmu 7
        assertThat(tenantContext.get()).isNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("podmetnuto TenantID zaglavlje obicnom korisniku NE mijenja firmu - token pobjeduje")
    void headerCannotOverrideTokenForUser() throws ServletException, IOException {
        when(companyRepository.existsByIdAndDeletedAtIsNull(7L)).thenReturn(true);
        loggedInAs(Role.USER, 7L);
        var chain = new RecordingChain(tenantContext);
        var response = new MockHttpServletResponse();

        // korisnik firme 7 pokusava zaglavljem doci do firme 99
        filter().doFilter(request("/api/templates", "99"), response, chain);

        assertThat(chain.tenantDuringCall).isEqualTo(7L);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("ni administratoru firme zaglavlje ne mijenja firmu - sire ovlasti ne znace siri doseg")
    void headerCannotOverrideTokenForTenantAdmin() throws ServletException, IOException {
        when(companyRepository.existsByIdAndDeletedAtIsNull(7L)).thenReturn(true);
        loggedInAs(Role.TENANT_ADMIN, 7L);
        var chain = new RecordingChain(tenantContext);
        var response = new MockHttpServletResponse();

        // admin firme 7 pokusava zaglavljem doci do firme 99
        filter().doFilter(request("/api/templates", "99"), response, chain);

        assertThat(chain.tenantDuringCall).isEqualTo(7L);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("ADMIN nema firmu u tokenu, pa mu se cita zaglavlje")
    void adminGetsCompanyFromHeader() throws ServletException, IOException {
        when(companyRepository.existsByIdAndDeletedAtIsNull(3L)).thenReturn(true);
        loggedInAs(Role.ADMIN, null);
        var chain = new RecordingChain(tenantContext);
        var response = new MockHttpServletResponse();

        filter().doFilter(request("/api/templates", "3"), response, chain);

        assertThat(chain.tenantDuringCall).isEqualTo(3L);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("ADMIN bez odabrane firme se odbija s 400")
    void adminWithoutHeaderIsRejected() throws ServletException, IOException {
        loggedInAs(Role.ADMIN, null);
        var chain = new RecordingChain(tenantContext);
        var response = new MockHttpServletResponse();

        filter().doFilter(request("/api/templates", null), response, chain);

        assertThat(chain.called).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("zaglavlje koje nije broj se odbija s 400")
    void nonNumericHeaderIsRejected() throws ServletException, IOException {
        loggedInAs(Role.ADMIN, null);
        var chain = new RecordingChain(tenantContext);
        var response = new MockHttpServletResponse();

        filter().doFilter(request("/api/templates", "abc"), response, chain);

        assertThat(chain.called).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("firma iz tokena koja je u meduvremenu arhivirana ili obrisana se odbija s 400")
    void archivedCompanyFromTokenIsRejected() throws ServletException, IOException {
        when(companyRepository.existsByIdAndDeletedAtIsNull(7L)).thenReturn(false);
        loggedInAs(Role.USER, 7L);
        var chain = new RecordingChain(tenantContext);
        var response = new MockHttpServletResponse();

        filter().doFilter(request("/api/templates", null), response, chain);

        assertThat(chain.called).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("bez prijavljenog korisnika se odbija - zadnja crta obrane iza JwtAuthFilter-a")
    void withoutAuthenticatedUserIsRejected() throws ServletException, IOException {
        var chain = new RecordingChain(tenantContext);
        var response = new MockHttpServletResponse();

        filter().doFilter(request("/api/templates", "3"), response, chain);

        assertThat(chain.called).isFalse();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("dnevnik je tenant-scoped kao i obrasci")
    void logsRouteIsTenantScoped() throws ServletException, IOException {
        when(companyRepository.existsByIdAndDeletedAtIsNull(7L)).thenReturn(true);
        loggedInAs(Role.USER, 7L);
        var chain = new RecordingChain(tenantContext);
        var response = new MockHttpServletResponse();

        filter().doFilter(request("/api/logs", null), response, chain);

        assertThat(chain.tenantDuringCall).isEqualTo(7L);
    }

    @Test
    @DisplayName("rute nad firmama nisu tenant-scoped - prolaze bez odredene firme")
    void companyRoutesAreNotTenantScoped() throws ServletException, IOException {
        // za /api/companies se ovaj filter uopce ne izvrsava (shouldNotFilter),
        // pa ni kontekst ni baza nisu dirani - ogranicava ih uloga, ne tenant
        var chain = new RecordingChain(tenantContext);
        var response = new MockHttpServletResponse();

        filter().doFilter(request("/api/companies", null), response, chain);

        assertThat(chain.called).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
