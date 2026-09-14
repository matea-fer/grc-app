package com.example.demo.service;

import com.example.demo.dto.LogEntryResponse;
import com.example.demo.dto.PageResponse;
import com.example.demo.model.LogEntry;
import com.example.demo.repository.LogEntryRepository;
import com.example.demo.tenant.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Citanje dnevnika. Odvojeno od {@link LogService} namjerno: pisanje ima poseban
 * ugovor (zasebna transakcija, greska se guta), a citanje je obican upit - spajanjem
 * bi se ta dva pravila pomijesala u jednom razredu.
 *
 * Vraca se samo dnevnik firme iz konteksta; ADMIN vidi dnevnik firme koju je odabrao
 * u traci, jednako kao i na ostalim ekranima.
 *
 * Do 12.08.2026. je vracao tvrdih 200 najnovijih zapisa i nista drugo - dakle na pitanje
 * "sto se dogadalo prosli mjesec" nije imao odgovor, iako su zapisi bili u bazi. Sada se
 * bira datum i lista po stranicama.
 */
@Service
public class LogQueryService {

    /** Isto koliko i kod zapisa - dva ekrana s razlicitim brojem redaka zbunjuju bez koristi. */
    public static final int PAGE_SIZE = SurveyResultService.PAGE_SIZE;

    private final LogEntryRepository repository;
    private final TenantContext tenantContext;

    public LogQueryService(LogEntryRepository repository, TenantContext tenantContext) {
        this.repository = repository;
        this.tenantContext = tenantContext;
    }

    /**
     * Jedna stranica dnevnika, najnovije prvo.
     *
     * @param from   datum od kojeg se gleda (ukljucivo), ili {@code null} za "sve od pocetka"
     * @param action samo akcije te vrste (npr. {@code RECORD_LOCKED}), ili {@code null} za sve;
     *               prazan tekst se cita kao "sve", jer ga tako salje prazan izbornik
     */
    public PageResponse<LogEntryResponse> getForCurrentCompany(int page, LocalDate from, String action) {
        Long companyId = tenantContext.require();
        int safePage = Math.max(page, 0);

        Page<LogEntry> found = repository.search(companyId, startOf(from),
                action == null || action.isBlank() ? null : action,
                PageRequest.of(safePage, PAGE_SIZE));

        List<LogEntryResponse> content = found.getContent().stream().map(LogEntryResponse::from).toList();
        return PageResponse.of(content, safePage, PAGE_SIZE, found.getTotalElements());
    }

    /**
     * Pocetak odabranog dana - u NASOJ vremenskoj zoni, ne u UTC-u.
     *
     * {@code created_at} se cuva kao trenutak (UTC), a korisnik bira lokalni datum. Da se
     * granica racuna kao ponoc po UTC-u, odabir "1.8." bi u ljetnom vremenu povukao i zapise
     * od 31.7. poslije 22 sata - dakle dnevnik bi vukao dan koji nitko nije trazio.
     */
    private Instant startOf(LocalDate from) {
        return from == null ? Instant.EPOCH : from.atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}
