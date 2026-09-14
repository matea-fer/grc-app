package com.example.demo.tenant;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.repository.CompanyRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Odreduje firmu (tenanta) za trenutni zahtjev i sprema je u {@link TenantContext},
 * da je nizvodni slojevi ne moraju rucno primati.
 *
 * Izvor firme ovisi o ulozi, i tu je najvaznije pravilo cijele izolacije:
 * <b>token pobjeduje zaglavlje</b>. Tko pripada firmi - a to su i USER i TENANT_ADMIN -
 * dobiva je iz potpisanog tokena, pa mu se zaglavlje {@code TenantID} u potpunosti
 * IGNORIRA i ne moze ga podmetnuti da bi vidio tude podatke. Samo globalni ADMIN u
 * tokenu firmu nema, nego je bira u gornjoj traci, pa se za njega (i samo za njega)
 * cita zaglavlje.
 *
 * Zato administrator firme prolazi kroz istu izolaciju kao obican korisnik: sire
 * ovlasti koje ima ticu se korisnickih racuna, a ne dosega po firmama.
 *
 * Fail-closed: ako se firma ne moze odrediti, zahtjev se odbija s 400 i NE ide dalje.
 * Nikad se ne pada natrag na "vrati sve" - to je bila izvorna rupa (filtriranje po
 * firmi zivjelo je u pregledniku, pa je backend svima vracao sve).
 *
 * Radi tek nakon {@link com.example.demo.auth.JwtAuthFilter} (@Order 2 vs 1), jer
 * bez prijavljenog korisnika nema iz cega odrediti firmu.
 */
@Component
@Order(2)
public class TenantFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);
    static final String TENANT_HEADER = "TenantID";

    // Rute koje rade nad podacima jedne firme. /api/companies i /api/users nisu ovdje:
    // one nisu vezane uz jednu firmu, nego se ogranicavaju ulogom (vidi servise).
    // /api/sbom je custom domenska znacajka, ali su njezini podaci firmini kao i svi ostali.
    private static final String[] TENANT_SCOPED = {"/api/templates", "/api/logs", "/api/sbom"};

    /**
     * Rute kojima firma KORISTI, ali nije uvjet: sifrarnici mogu biti globalni.
     *
     * Za njih se firma odredi ako se moze, a ako ne moze - zahtjev svejedno prolazi, s
     * praznim kontekstom. Servis tada vrati (i dopusti mijenjati) samo ono sto nije
     * vezano ni uz jednu firmu. Bez ove razlike bi globalni administrator morao odabrati
     * firmu i za uredivanje globalnog sifrarnika, jer filter odbija prije kontrolera i
     * ondje se jos ne zna o kojem je dosegu rijec.
     */
    private static final String[] TENANT_OPTIONAL = {"/api/codebooks"};

    private final CompanyRepository companyRepository;
    private final TenantContext tenantContext;
    private final AuthContext authContext;

    public TenantFilter(CompanyRepository companyRepository, TenantContext tenantContext, AuthContext authContext) {
        this.companyRepository = companyRepository;
        this.tenantContext = tenantContext;
        this.authContext = authContext;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !matches(path, TENANT_SCOPED) && !matches(path, TENANT_OPTIONAL);
    }

    private static boolean matches(String path, String[] prefixes) {
        for (String prefix : prefixes) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Long companyId = resolveCompany(request);
        if (companyId == null && !matches(request.getRequestURI(), TENANT_OPTIONAL)) {
            log.warn("Rejected {} {} - firma nije odredena", request.getMethod(), request.getRequestURI());
            writeError(response);
            return;
        }

        // kod neobaveznih ruta kontekst ostaje prazan; servis iz toga zna da smije
        // raditi samo s onim sto nije vezano uz firmu
        if (companyId != null) {
            tenantContext.set(companyId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // Nit se vraca u zajednicki bazen; bez ovoga bi sljedeci zahtjev na njoj naslijedio tudu firmu.
            tenantContext.clear();
        }
    }

    /**
     * Firma za ovaj zahtjev, ili null ako se ne moze odrediti.
     *
     * Za obicnog korisnika je to firma iz tokena; zaglavlje se ne gleda. Za ADMIN-a
     * (koji firmu u tokenu nema) cita se {@code TenantID}. U oba slucaja firma mora jos
     * postojati I BITI AKTIVNA - token moze pokazivati na firmu arhiviranu nakon prijave.
     * Arhivirana firma se ovdje ponasa kao da je nema: njezini podaci postoje, ali kroz
     * aplikaciju do njih ne vodi nijedan put dok se ne vrati iz arhive.
     */
    private Long resolveCompany(HttpServletRequest request) {
        AuthenticatedUser user = authContext.get();
        if (user == null) {
            // JwtAuthFilter je vec trebao odbiti takav zahtjev; ovo je zadnja crta obrane.
            return null;
        }
        Long fromToken = user.companyId();
        Long companyId = fromToken != null ? fromToken : parseHeader(request.getHeader(TENANT_HEADER));
        if (companyId == null) {
            return null;
        }
        return companyRepository.existsByIdAndDeletedAtIsNull(companyId) ? companyId : null;
    }

    /** @return broj iz zaglavlja, ili null ako zaglavlje nedostaje ili nije broj */
    private Long parseHeader(String rawHeader) {
        if (rawHeader == null || rawHeader.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(rawHeader.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void writeError(HttpServletResponse response) throws IOException {
        // Isti oblik tijela ({message}) kao kod ostalih gresaka - filter je prije DispatcherServleta,
        // pa ga @RestControllerAdvice ne hvata i JSON se ovdje ispisuje rucno. Poruka je konstantna
        // (bez korisnickog unosa u sebi), pa se moze upisati doslovno, bez serializacije.
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"Nedostaje ili je nevažeće zaglavlje TenantID.\"}");
    }
}
