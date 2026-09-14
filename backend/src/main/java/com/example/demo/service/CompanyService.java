package com.example.demo.service;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.CompanyResponse;
import com.example.demo.dto.CreateCompanyRequest;
import com.example.demo.exception.InvalidCompanyException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Company;
import com.example.demo.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Firme. Jedini resurs koji nije ogranicen tenantom nego ULOGOM: mijenjati ih smije
 * samo GLOBALNI administrator. Svi ostali - i obican korisnik i administrator firme -
 * kroz popis vide jedino svoju firmu (iz nje se puni natpis u traci, pa im popis
 * tudih firmi nista ne znaci).
 *
 * Stvaranje firmi namjerno ostaje samo globalnom administratoru: administrator firme
 * upravlja SVOJOM firmom, a ne time koje firme postoje.
 *
 * <b>Brisanje je dvokoracno</b> (od 10.08.2026.): {@link #delete} firmu samo ARHIVIRA, a
 * podatke fizicki uklanja tek {@link #purge}, kao zasebna radnja nad vec arhiviranom
 * firmom. Razlog je jednostavan: prije toga je jedan pogresan klik nepovratno uklanjao
 * korisnike firme, a njezini obrasci, zapisi i prilozi ostajali su u bazi bez ijednog puta
 * do njih - dakle najgori od oba svijeta. Sada je pogresan klik popravljiv ({@link #restore}),
 * a kad se stvarno zeli osloboditi prostor, uklanja se sve.
 */
@Service
public class CompanyService {
    private static final Logger log = LoggerFactory.getLogger(CompanyService.class);
    private final CompanyRepository repository;
    private final AuthContext authContext;
    private final LogService logService;
    private final CompanyPurgeService purgeService;

    public CompanyService(CompanyRepository repository,
                          AuthContext authContext,
                          LogService logService,
                          CompanyPurgeService purgeService) {
        this.repository = repository;
        this.authContext = authContext;
        this.logService = logService;
        this.purgeService = purgeService;
    }

    /** Aktivne firme. Arhivirane se ovdje NE pojavljuju - za njih postoji {@link #getArchived}. */
    public List<CompanyResponse> getAll() {
        AuthenticatedUser user = authContext.require();
        if (!user.isAdmin()) {
            // vezan uz firmu (USER ili TENANT_ADMIN): samo vlastita, i to ako je jos aktivna
            return repository.findByIdAndDeletedAtIsNull(user.companyId()).stream()
                    .map(CompanyResponse::from).toList();
        }
        return repository.findByDeletedAtIsNull().stream().map(CompanyResponse::from).toList();
    }

    /**
     * Arhivirane firme - samo za globalnog administratora.
     *
     * Korisnik arhivirane firme ovaj popis ne moze ni zatraziti: dotle ne dolazi jer se ne
     * moze ni prijaviti.
     */
    public List<CompanyResponse> getArchived() {
        authContext.requireAdmin();
        return repository.findByDeletedAtIsNotNullOrderByDeletedAtDesc().stream()
                .map(CompanyResponse::from).toList();
    }

    public CompanyResponse create(CreateCompanyRequest request) {
        authContext.requireAdmin();
        Company company = new Company(request.name());
        Company saved = repository.save(company);
        log.info("Created Company id={}", saved.getId());
        // vezano uz novu firmu, da zapis zavrsi u NJEZINU dnevniku, a ne nigdje
        logService.recordForCompany(saved.getId(), "COMPANY_CREATED",
                "Firma \"" + saved.getName() + "\" (id=" + saved.getId() + ")");
        return CompanyResponse.from(saved);
    }

    public CompanyResponse update(Long id, CreateCompanyRequest request) {
        authContext.requireAdmin();
        // arhivirana firma se ne preimenuje: naziv pod kojim je arhivirana je ono po cemu
        // se prepoznaje na popisu arhive
        Company company = requireActive(id);
        company.setName(request.name());
        Company saved = repository.save(company);
        log.info("Renamed Company id={}", saved.getId());
        logService.recordForCompany(id, "COMPANY_RENAMED",
                "Firma id=" + id + " preimenovana u \"" + saved.getName() + "\"");
        return CompanyResponse.from(saved);
    }

    /**
     * Arhivira firmu: nestaje s popisa, njezini se korisnici vise ne mogu prijaviti i nijedan
     * zahtjev nad njezinim podacima ne prolazi ({@code TenantFilter}, {@code AuthService}).
     *
     * Korisnici se pritom NE brisu, za razliku od prijasnjeg ponasanja. Bez njih vracanje ne
     * bi vratilo firmu nego samo njezino ime - a racuni arhivirane firme ionako ne mogu nista,
     * jer ih prijava odbija. Fizicki nestaju tek pri {@link #purge}.
     */
    @Transactional
    public void delete(Long id) {
        authContext.requireAdmin();
        Company company = requireActive(id);
        company.setDeletedAt(Instant.now());
        repository.save(company);
        log.info("Archived Company id={}", id);
        logService.recordForCompany(id, "COMPANY_ARCHIVED",
                "Firma \"" + company.getName() + "\" (id=" + id + ") arhivirana");
    }

    /** Vraca arhiviranu firmu u rad; svi njezini podaci su i dalje netaknuti. */
    @Transactional
    public CompanyResponse restore(Long id) {
        authContext.requireAdmin();
        Company company = requireArchived(id);
        company.setDeletedAt(null);
        Company saved = repository.save(company);
        log.info("Restored Company id={}", id);
        logService.recordForCompany(id, "COMPANY_RESTORED",
                "Firma \"" + saved.getName() + "\" (id=" + id + ") vracena iz arhive");
        return CompanyResponse.from(saved);
    }

    /**
     * Trajno uklanja firmu i sve njezine podatke. Nepovratno.
     *
     * Dopusteno je SAMO nad arhiviranom firmom, i to je jedina stvarna zastita koju ovaj
     * postupak ima: aktivna se firma ne moze isprazniti u jednom koraku, nego se mora prvo
     * arhivirati - dakle nestati iz upotrebe i biti primijecena kao takva - pa tek onda
     * isprazniti. Dva razdvojena koraka, dvije odluke.
     *
     * Dnevnik prezivi, s maknutom vezom na firmu; vidi {@link CompanyPurgeService}.
     */
    @Transactional
    public void purge(Long id) {
        authContext.requireAdmin();
        Company company = requireArchived(id);
        String name = company.getName();

        CompanyPurgeService.PurgeSummary summary = purgeService.purge(id);
        repository.delete(company);
        log.info("Purged Company id={} name={}", id, name);

        // zapis NAMJERNO ide bez firme: firme vise nema, pa bi vezan zapis bio jedini redak
        // koji pokazuje na nepostojeci id - i to onaj koji je upravo maknuo sve ostale
        logService.recordForCompany(null, "COMPANY_PURGED",
                "Firma \"" + name + "\" (id=" + id + ") trajno ispraznjena: " + summary.describe());
    }

    /** Firma koja postoji i nije arhivirana; inace 404. */
    private Company requireActive(Long id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Company", id));
    }

    /**
     * Firma koja postoji i JEST arhivirana.
     *
     * Razlika u odgovoru je namjerna: firma koje nema daje 404, a postojeca aktivna firma
     * daje 400 - ona nije skrivena od pozivatelja (vidi je na popisu), nego je radnja
     * pogresna za njezino stanje. Pravilo "tudi podatak daje 404" ovdje ne vrijedi jer se
     * nista ne otkriva: pozivatelj je globalni ADMIN i firmu ionako vec vidi.
     */
    private Company requireArchived(Long id) {
        Company company = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Company", id));
        if (!company.isArchived()) {
            throw new InvalidCompanyException("Firma \"" + company.getName() + "\" nije arhivirana.");
        }
        return company;
    }
}
