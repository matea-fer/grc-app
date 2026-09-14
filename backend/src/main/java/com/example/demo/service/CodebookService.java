package com.example.demo.service;

import com.example.demo.auth.AuthContext;
import com.example.demo.auth.AuthenticatedUser;
import com.example.demo.dto.CodebookResponse;
import com.example.demo.dto.CreateCodebookRequest;
import com.example.demo.dto.UpdateCodebookRequest;
import com.example.demo.exception.CodebookInUseException;
import com.example.demo.exception.DuplicateCodebookNameException;
import com.example.demo.exception.ForbiddenException;
import com.example.demo.exception.InvalidCodebookException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookItem;
import com.example.demo.model.CodebookScope;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.CodebookRepository;
import com.example.demo.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Zaglavlja sifrarnika, i cuvar pristupa za sve sto visi ispod njih.
 *
 * Doseg citanja i pisanja nije isti, i to je namjerno - sifrarnik je KONFIGURACIJA,
 * a ne podatak: obican korisnik ga koristi, ne mijenja.
 *
 * <pre>
 *                citanje                       pisanje
 *   GLOBAL       svi prijavljeni               samo globalni ADMIN
 *   TENANT       svi u toj firmi               ADMIN + TENANT_ADMIN te firme
 * </pre>
 *
 * Dvije vrste odbijanja, i razlika je ista kao drugdje u projektu:
 *   404 kad sifrarnik nije u dosegu pozivatelja - tudi se ne smije ni otkriti,
 *   403 kad ga smije vidjeti, ali ne i mijenjati.
 *
 * Firma dolazi iz {@link TenantContext}, nikad iz tijela zahtjeva. Kontekst ovdje
 * smije biti PRAZAN (vidi TenantFilter.TENANT_OPTIONAL): globalni administrator koji
 * jos nije odabrao firmu tada radi samo s globalnim sifrarnicima.
 */
@Service
public class CodebookService {
    private static final Logger log = LoggerFactory.getLogger(CodebookService.class);

    private final CodebookRepository repository;
    private final CodebookItemRepository itemRepository;
    private final CodebookUsage codebookUsage;
    private final TenantContext tenantContext;
    private final AuthContext authContext;
    private final LogService logService;

    public CodebookService(CodebookRepository repository,
                           CodebookItemRepository itemRepository,
                           CodebookUsage codebookUsage,
                           TenantContext tenantContext,
                           AuthContext authContext,
                           LogService logService) {
        this.repository = repository;
        this.itemRepository = itemRepository;
        this.codebookUsage = codebookUsage;
        this.tenantContext = tenantContext;
        this.authContext = authContext;
        this.logService = logService;
    }

    /**
     * Sifrarnici dostupni pozivatelju: svi globalni + oni njegove firme.
     *
     * @param scope  suzenje na jedan doseg, ili null za oba
     * @param search dio naziva, ili null/prazno za sve
     */
    public List<CodebookResponse> list(CodebookScope scope, String search) {
        authContext.require();
        Long companyId = tenantContext.get();
        String namePattern = namePattern(search);

        List<Codebook> found = scope == null
                ? repository.findVisible(companyId, namePattern)
                : repository.findVisibleByScope(companyId, namePattern, scope);

        Map<Long, Long> counts = countsFor(found);
        return found.stream()
                .map(codebook -> CodebookResponse.from(codebook, counts.getOrDefault(codebook.getId(), 0L)))
                .toList();
    }

    /**
     * Jedan sifrarnik. Ekran sa stavkama iz njega uzima naziv i doseg (a doseg odlucuje
     * smije li se uopce uredivati), pa mu inace ne bi preostalo nego dohvatiti cijeli
     * popis i traziti svoj redak.
     */
    public CodebookResponse getOne(Long id) {
        Codebook codebook = requireAccessible(id);
        return CodebookResponse.from(codebook, itemRepository.findByCodebookIdOrderBySortOrderAsc(id).size());
    }

    public CodebookResponse create(CreateCodebookRequest request) {
        Long companyId = requireEditorFor(request.scope());
        String name = request.name().trim();
        requireNameFree(request.scope(), companyId, name);

        Codebook saved = repository.save(new Codebook(name, request.scope(), companyId));
        log.info("Created Codebook id={} scope={} companyId={}", saved.getId(), saved.getScope(), companyId);
        logService.recordForCompany(companyId, "CODEBOOK_CREATED",
                "Šifrarnik \"" + name + "\" (" + saved.getScope() + ", id=" + saved.getId() + ")");
        return CodebookResponse.from(saved, 0);
    }

    public CodebookResponse rename(Long id, UpdateCodebookRequest request) {
        Codebook codebook = requireEditable(id);
        String name = request.name().trim();
        if (!name.equalsIgnoreCase(codebook.getName())) {
            requireNameFree(codebook.getScope(), codebook.getCompanyId(), name);
        }

        String before = codebook.getName();
        codebook.setName(name);
        Codebook saved = repository.save(codebook);
        log.info("Renamed Codebook id={}", id);
        logService.recordForCompany(codebook.getCompanyId(), "CODEBOOK_RENAMED",
                "Šifrarnik \"" + before + "\" (id=" + id + ") preimenovan u \"" + name + "\"");
        return CodebookResponse.from(saved, itemRepository.findByCodebookIdOrderBySortOrderAsc(id).size());
    }

    /** Brise sifrarnik i sve njegove stavke - kao sto brisanje obrasca odnosi i njegove zapise. */
    @Transactional
    public void delete(Long id) {
        Codebook codebook = requireEditable(id);
        requireNotInUse(codebook);

        List<CodebookItem> items = itemRepository.findByCodebookIdOrderBySortOrderAsc(id);
        itemRepository.deleteAll(items);
        repository.delete(codebook);

        log.info("Deleted Codebook id={} with {} item(s)", id, items.size());
        logService.recordForCompany(codebook.getCompanyId(), "CODEBOOK_DELETED",
                "Šifrarnik \"" + codebook.getName() + "\" (id=" + id + ") i " + items.size() + " stavki");
    }

    // --- pristup ---

    /**
     * Sifrarnik koji pozivatelj smije VIDJETI. Tudi ili nepostojeci jednako zavrsavaju
     * kao 404 - poziv ne smije razlikovati "ne postoji" od "nije tvoj".
     *
     * Zovu je i {@link CodebookItemService} prije svakog dodira stavki: stavka u sebi ne
     * nosi firmu, pa je ovo jedino mjesto na kojem se provjerava cija je.
     */
    public Codebook requireAccessible(Long id) {
        AuthenticatedUser user = authContext.require();
        Codebook codebook = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Codebook", id));

        if (codebook.getScope() == CodebookScope.GLOBAL) {
            return codebook;
        }
        // TENANT: mora biti firma iz konteksta. Globalni administrator koji nije odabrao
        // firmu ne vidi nicije - kao ni na ostalim ekranima.
        Long companyId = tenantContext.get();
        if (companyId == null || !companyId.equals(codebook.getCompanyId())) {
            log.warn("Codebook id={} nije u dosegu korisnika {}", id, user.username());
            throw new ResourceNotFoundException("Codebook", id);
        }
        return codebook;
    }

    /**
     * Sifrarnik koji pozivatelj smije MIJENJATI.
     *
     * Prvo se provjerava vidljivost (404 za tudeg), pa tek onda ovlast (403). Obrnutim
     * redoslijedom bi 403 nad tudim sifrarnikom odao da taj sifrarnik postoji.
     */
    public Codebook requireEditable(Long id) {
        Codebook codebook = requireAccessible(id);
        requireEditorFor(codebook.getScope());
        return codebook;
    }

    /**
     * Smije li pozivatelj uopce stvarati/mijenjati sifrarnike tog dosega.
     *
     * @return firma kojoj sifrarnik pripada (null za GLOBAL) - uzeta iz konteksta, ne iz zahtjeva
     */
    private Long requireEditorFor(CodebookScope scope) {
        AuthenticatedUser user = authContext.require();

        if (scope == CodebookScope.GLOBAL) {
            if (!user.isAdmin()) {
                throw new ForbiddenException("Globalni šifrarnik smije uređivati samo globalni administrator.");
            }
            return null;
        }

        // TENANT: obje administratorske uloge, ali svaka samo unutar svoje firme -
        // firmu odreduje kontekst (token, odnosno odabir u traci za globalnog ADMIN-a)
        if (!user.isAdmin() && !user.isTenantAdmin()) {
            throw new ForbiddenException("Šifrarnik smije uređivati samo administrator.");
        }
        Long companyId = tenantContext.get();
        if (companyId == null) {
            throw new InvalidCodebookException("Za šifrarnik firme mora biti odabrana firma.");
        }
        return companyId;
    }

    // --- pomocne ---

    /**
     * Pretraga pretvorena u gotov LIKE uzorak, u malim slovima.
     *
     * Prazna pretraga daje {@code %} (sve), a ne null - parametar koji je nekad null
     * stize u bazu bez tipa, sto je jednom vec srusilo cijeli upit. Ovako uzorak uvijek
     * postoji i uvijek je tekst.
     *
     * {@code %} i {@code _} iz korisnickog unosa su u LIKE-u dzokeri, pa se ekraniraju -
     * inace bi pretraga "50%" nasla sve sto pocinje s "50". Znak za ekraniranje je
     * {@code !} (a ne obrnuta kosa crta) da se ne mora dvostruko izbjegavati u JPQL-u;
     * i sam se mora ekranirati, i to prvi, prije nego ga uvedu ostale zamjene.
     */
    private String namePattern(String search) {
        if (search == null || search.isBlank()) {
            return "%";
        }
        String escaped = search.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    private void requireNameFree(CodebookScope scope, Long companyId, String name) {
        boolean taken = companyId == null
                ? repository.existsByScopeAndCompanyIdIsNullAndNameIgnoreCase(scope, name)
                : repository.existsByScopeAndCompanyIdAndNameIgnoreCase(scope, companyId, name);
        if (taken) {
            throw new DuplicateCodebookNameException(name);
        }
    }

    /**
     * Sifrarnik na koji pokazuje ijedan stupac se ne brise.
     *
     * Ovdje je dovoljan SAM STUPAC, bez ijednog upisanog zapisa: obrisati sifrarnik ispod
     * stupca ostavilo bi stupac koji tvrdi da mu vrijednosti dolaze iz necega cega nema, pa
     * bi svaki sljedeci unos u njega pao. Kod pojedine stavke je mjerilo druga -
     * vidi {@link CodebookItemService}.
     */
    private void requireNotInUse(Codebook codebook) {
        List<CodebookUsage.ColumnUse> uses = codebookUsage.columnsUsing(codebook);
        if (!uses.isEmpty()) {
            throw new CodebookInUseException("Šifrarnik \"" + codebook.getName() + "\" se koristi ("
                    + uses.getFirst().describe() + (uses.size() > 1 ? " i još " + (uses.size() - 1) + " stupac/stupaca" : "")
                    + "), pa se ne može obrisati. Prvo promijenite ili obrišite taj stupac.");
        }
    }

    /** Broj stavki po sifrarniku, jednim upitom za cijeli popis (ne N upita za N redaka). */
    private Map<Long, Long> countsFor(List<Codebook> codebooks) {
        if (codebooks.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = codebooks.stream().map(Codebook::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        for (CodebookItemRepository.CodebookItemCount row : itemRepository.countByCodebookIds(ids)) {
            counts.put(row.getCodebookId(), row.getItemCount());
        }
        return counts;
    }
}
