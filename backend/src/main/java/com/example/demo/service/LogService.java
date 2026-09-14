package com.example.demo.service;

import com.example.demo.auth.AuthContext;
import com.example.demo.model.LogEntry;
import com.example.demo.repository.LogEntryRepository;
import com.example.demo.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Upisuje audit zapise o izvrsenim akcijama. Servisi ga zovu tek kad je akcija
 * stvarno uspjela, s ljudski citljivim opisom sto je dirano.
 *
 * Svaki zapis nosi TKO ({@link AuthContext}) i NAD CIJOM FIRMOM ({@link TenantContext}).
 * Oboje se cita iz konteksta da se ne mora provlaciti kroz potpise metoda; za akcije
 * gdje kontekst ne vrijedi postoje preopterecenja s eksplicitnim vrijednostima.
 *
 * Dvije mjere da audit NIKAD ne obori akciju koja je vec uspjela:
 *  - {@code REQUIRES_NEW}: upis ide u zasebnu transakciju, pa njegov eventualni
 *    rollback ne povlaci glavnu (i obrnuto),
 *  - try/catch: greska pri upisu se samo zabiljezi u aplikacijski log.
 */
@Service
public class LogService {
    private static final Logger log = LoggerFactory.getLogger(LogService.class);

    private final LogEntryRepository repository;
    private final TenantContext tenantContext;
    private final AuthContext authContext;

    public LogService(LogEntryRepository repository, TenantContext tenantContext, AuthContext authContext) {
        this.repository = repository;
        this.tenantContext = tenantContext;
        this.authContext = authContext;
    }

    /** Akcija unutar firme iz konteksta - uobicajen slucaj (obrasci, stupci, zapisi). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String detail) {
        save(action, detail, tenantContext.get(), authContext.username());
    }

    /**
     * Akcija koja se tice odredene firme, ali se izvrsava izvan tenant konteksta -
     * npr. ADMIN stvara ili brise firmu, ili joj dodaje korisnika. Bez ovoga bi takav
     * zapis dobio {@code companyId = null} i ne bi se pojavio ni u cijem dnevniku.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordForCompany(Long companyId, String action, String detail) {
        save(action, detail, companyId, authContext.username());
    }

    /**
     * Akcija prije nego prijava uopce postoji - sama prijava. Korisnicko ime dolazi
     * iz zahtjeva (kod neuspjele prijave je to pokusano ime, koje mozda ne postoji).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String detail, Long companyId, String username) {
        save(action, detail, companyId, username);
    }

    /**
     * Zajednicki upis. Namjerno je PRIVATAN i bez anotacije: da je javan i anotiran,
     * pozivi iz metoda iznad isli bi mimo Springova proxyja (samopoziv), pa
     * {@code REQUIRES_NEW} ne bi vrijedio - audit bi zavrsio u glavnoj transakciji
     * i njegov bi ga pad mogao povuci sa sobom. Ovako je anotacija na svakoj ulaznoj
     * tocki, gdje proxy stvarno djeluje.
     */
    private void save(String action, String detail, Long companyId, String username) {
        try {
            repository.save(new LogEntry(companyId, username, action, detail, Instant.now()));
        } catch (RuntimeException ex) {
            // audit ne smije oboriti akciju koja je vec uspjela
            log.warn("Audit log upis nije uspio (action={}): {}", action, ex.getMessage());
        }
    }
}
