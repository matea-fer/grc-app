package com.example.demo.service;

import com.example.demo.dto.CodebookItemResponse;
import com.example.demo.dto.SaveCodebookItemsRequest;
import com.example.demo.exception.CodebookInUseException;
import com.example.demo.exception.DuplicateCodebookItemCodeException;
import com.example.demo.exception.InvalidCodebookException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookItem;
import com.example.demo.model.CodebookScope;
import com.example.demo.repository.CodebookItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stavke jednog sifrarnika.
 *
 * Svaka operacija prvo prode kroz {@link CodebookService} - {@code requireAccessible}
 * za citanje, {@code requireEditable} za pisanje. Stavka u sebi ne nosi firmu, pa je
 * sifrarnik jedino mjesto na kojem se zna cija je; isto kao sto zapis pripada firmi
 * preko svog obrasca.
 *
 * Spremanje je PUNA ZAMJENA popisa, ne spajanje: sto god stigne postaje cijeli sadrzaj
 * sifrarnika. Identitet ide preko id-a stavke, a ne preko sifre - da se sifra smije
 * ispraviti, a da stavka ostane ista.
 */
@Service
public class CodebookItemService {
    private static final Logger log = LoggerFactory.getLogger(CodebookItemService.class);

    private final CodebookItemRepository repository;
    private final CodebookService codebookService;
    private final CodebookUsage codebookUsage;
    private final LogService logService;
    private final EntityManager entityManager;

    public CodebookItemService(CodebookItemRepository repository,
                               CodebookService codebookService,
                               CodebookUsage codebookUsage,
                               LogService logService,
                               EntityManager entityManager) {
        this.repository = repository;
        this.codebookService = codebookService;
        this.codebookUsage = codebookUsage;
        this.logService = logService;
        this.entityManager = entityManager;
    }

    public List<CodebookItemResponse> getForCodebook(Long codebookId) {
        codebookService.requireAccessible(codebookId);
        return repository.findByCodebookIdOrderBySortOrderAsc(codebookId).stream()
                .map(CodebookItemResponse::from)
                .toList();
    }

    /**
     * Sifre stavki cijem NAZIVU (ili samoj sifri) odgovara upisani tekst.
     *
     * Postoji zbog pretrage zapisa: u retku stoji sifra ("ZG"), a korisnik trazi ono sto vidi
     * ("Zagreb"). Prijevod ide ovuda umjesto spajanja tablica u upitu nad zapisima - sifrarnik
     * je malen, pa je ovo jedan jeftin upit, a upit nad zapisima ostaje bez pravila o dosegu
     * sifrarnika.
     *
     * Trazi se i po sifri, jer je onaj tko utipka "ZG" ocito htio bas nju.
     *
     * Iskljucene stavke se NE preskacu: one se ne nude za nove unose, ali zapisi koji su ih
     * dobili dok su bile aktivne i dalje postoje - i moraju se dati pronaci.
     *
     * Ovdje se NE provjerava smije li pozivatelj taj sifrarnik vidjeti - isto kao u
     * {@link SchemaValidator}. Sifrarnik dolazi iz stupca obrasca koji je vec potvrden kao
     * firmin, a pitanje pristupa je rijeseno kad je stupac deklariran.
     */
    public List<String> codesMatching(Long codebookId, String text) {
        if (codebookId == null || text == null || text.isBlank()) {
            return List.of();
        }
        String needle = text.trim().toLowerCase();
        List<String> codes = new ArrayList<>();
        for (CodebookItem item : repository.findByCodebookIdOrderBySortOrderAsc(codebookId)) {
            if (item.getName().toLowerCase().contains(needle) || item.getCode().toLowerCase().contains(needle)) {
                codes.add(item.getCode());
            }
        }
        return codes;
    }

    /**
     * Spremi cijeli popis odjednom: postojece stavke se azuriraju, nove dodaju, a one
     * kojih u popisu nema - brisu.
     *
     * {@code sortOrder} se NE cita iz zahtjeva nego se dodjeljuje po polozaju u nizu.
     * Tako klijent salje redoslijed, a ne brojeve, pa se ne moze dogoditi popis s dvije
     * stavke na istom mjestu.
     */
    @Transactional
    public List<CodebookItemResponse> save(Long codebookId, SaveCodebookItemsRequest request) {
        Codebook codebook = codebookService.requireEditable(codebookId);
        rejectDuplicateCodes(request.items());

        List<CodebookItem> existing = repository.findByCodebookIdOrderBySortOrderAsc(codebookId);
        Map<Long, CodebookItem> byId = new HashMap<>();
        for (CodebookItem item : existing) {
            byId.put(item.getId(), item);
        }

        List<CodebookItem> wanted = new ArrayList<>();
        Set<Long> kept = new HashSet<>();
        int order = 0;
        for (SaveCodebookItemsRequest.CodebookItemInput input : request.items()) {
            CodebookItem item = resolve(codebookId, input, byId);
            kept.add(item.getId());
            item.setCode(input.code().trim());
            item.setName(input.name().trim());
            item.setActive(input.active());
            item.setSortOrder(order++);
            wanted.add(item);
        }

        // Brisanje ide PRIJE upisa, i to s flushom: bez toga bi nova stavka koja preuzima
        // sifru upravo maknute pala na jedinstvenom ogranicenju, jer bi stara jos bila u bazi.
        List<CodebookItem> removed = existing.stream().filter(item -> !kept.contains(item.getId())).toList();
        if (!removed.isEmpty()) {
            requireNotInUse(codebook, removed);
            repository.deleteAll(removed);
            repository.flush();
        }

        try {
            repository.saveAll(wanted);
            repository.flush();
        } catch (DataIntegrityViolationException ex) {
            // Zamjena sifara izmedu dvije postojece stavke ("OPEN" <-> "CLOSED") nije duplikat
            // u poslanom popisu, ali usred flusha nakratko jest u bazi. Ogranicenje je
            // neodgodivo, pa se to vidi tek ovdje - bolje 409 s razumljivom porukom nego 500.
            log.warn("Spremanje stavki sifrarnika id={} palo na ogranicenju: {}", codebookId, ex.getMessage());
            throw new DuplicateCodebookItemCodeException("(zamjena šifara u istom spremanju)");
        }

        // Stavke su u vlastitoj tablici, pa njihova izmjena ne dira redak sifrarnika i @Version
        // sam od sebe ne bi porastao. Bez ovoga bi dva paralelna spremanja popisa (a to je puna
        // zamjena) zavrsila tako da drugo pregazi prvo, bez ijednog traga.
        entityManager.lock(codebook, LockModeType.OPTIMISTIC_FORCE_INCREMENT);

        log.info("Saved {} item(s) for Codebook id={} ({} removed)", wanted.size(), codebookId, removed.size());
        logService.recordForCompany(codebook.getCompanyId(), "CODEBOOK_ITEMS_SAVED",
                "Šifrarnik \"" + codebook.getName() + "\" (id=" + codebookId + "): "
                        + wanted.size() + " stavki, uklonjeno " + removed.size());

        return wanted.stream().map(CodebookItemResponse::from).toList();
    }

    /**
     * Sifra stavke koja nosi zadani naziv - stvorena ako je jos nema.
     *
     * Ovo je JEDINI put kojim obican korisnik moze dopuniti sifrarnik, i postoji zbog
     * stupca s ukljucenim {@code allowNewValues}: korisnik pri unosu zapisa upise vrijednost
     * slobodno, a ona se odmah pretvori u stavku sifrarnika. Zato se ovdje trazi samo
     * {@code requireAccessible} (citanje), a ne {@code requireEditable} - da to ne bi bila
     * rupa u pravilu "sifrarnike ureduje samo administrator", izuzetak je namjerno uzak:
     *
     *   - iskljucivo DODAVANJE; postojeca stavka se ne dira ni u nazivu, ni u sifri, ni u
     *     tome je li aktivna, i nista se ne brise;
     *   - nikad u GLOBAL sifrarnik - unos jedne firme ne smije mijenjati popis svima
     *     ostalima. Shema takav stupac odbija jos pri definiranju, ovo je druga brava;
     *   - sifru dodjeljuje server iz naziva, jer je korisnik pri unosu zapisa uopce ne vidi;
     *   - upisuje se u dnevnik pod vlastitom akcijom, da se u njemu razlikuje od uredivanja.
     *
     * Naziv koji vec postoji (bez obzira na velika slova) NE stvara novu stavku - upravo to
     * je i smisao: "Zagreb" i "zagreb" moraju zavrsiti kao jedna vrijednost, inace bi stupac
     * proizveo tocno onaj nered zbog kojeg sifrarnici postoje.
     */
    @Transactional
    public String ensureCode(Long codebookId, String rawName) {
        Codebook codebook = codebookService.requireAccessible(codebookId);
        if (codebook.getScope() == CodebookScope.GLOBAL) {
            throw new InvalidCodebookException(
                    "U globalni šifrarnik se ne može upisivati kroz unos zapisa - vrijedi za sve firme.");
        }

        String name = rawName.trim();
        List<CodebookItem> items = repository.findByCodebookIdOrderBySortOrderAsc(codebookId);
        for (CodebookItem item : items) {
            // sifra se usporeduje jednako kao naziv: korisnik koji je upisao postojecu sifru
            // ("ZG") ocito misli na tu stavku, a ne na novu vrijednost istog imena
            if (item.getName().equalsIgnoreCase(name) || item.getCode().equalsIgnoreCase(name)) {
                return item.getCode();
            }
        }

        int sortOrder = items.stream().mapToInt(CodebookItem::getSortOrder).max().orElse(-1) + 1;
        CodebookItem created = repository.save(
                new CodebookItem(codebookId, freeCode(name, items), name, true, sortOrder));
        repository.flush();

        log.info("Stavka \"{}\" dodana u sifrarnik id={} kroz unos zapisa", created.getCode(), codebookId);
        logService.recordForCompany(codebook.getCompanyId(), "CODEBOOK_ITEM_ADDED_ON_ENTRY",
                "Šifrarnik \"" + codebook.getName() + "\" (id=" + codebookId + "): nova stavka \""
                        + name + "\" (šifra " + created.getCode() + ") nastala unosom zapisa");
        return created.getCode();
    }

    /**
     * Sifra izvedena iz naziva: velika slova, bez dijakritike, razmaci i interpunkcija u
     * podvlaku. Dijakritika se mice jer sifra zavrsi u podacima i u izvozima, gdje "Ð" i
     * "Č" znaju prezivjeti krivo; naziv, koji korisnik i vidi, ostaje netaknut.
     *
     * Sudar se rjesava brojcanim nastavkom, a ne odbijanjem: dvije razlicite vrijednosti
     * ("Split (grad)" i "Split") daju istu osnovu, a korisnik koji unosi zapis nema kako
     * znati da mu je sifra zauzeta - niti ga se to tice.
     */
    private String freeCode(String name, List<CodebookItem> existing) {
        String base = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replace('Đ', 'D')
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isEmpty()) {
            base = "VRIJEDNOST";
        }
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }

        Set<String> taken = existing.stream()
                .map(item -> item.getCode().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (!taken.contains(base)) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = base + "_" + suffix;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * Postojeca stavka po id-u, ili nova. Id koji ne pripada ovom sifrarniku zavrsava kao
     * 404 - inace bi se izmjenom tudeg id-a moglo pisati po stavci drugog sifrarnika.
     */
    private CodebookItem resolve(Long codebookId,
                                 SaveCodebookItemsRequest.CodebookItemInput input,
                                 Map<Long, CodebookItem> byId) {
        if (input.id() == null) {
            CodebookItem item = new CodebookItem();
            item.setCodebookId(codebookId);
            return item;
        }
        CodebookItem existing = byId.get(input.id());
        if (existing == null) {
            throw new ResourceNotFoundException("CodebookItem", input.id());
        }
        return existing;
    }

    /**
     * Ista sifra dva puta u poslanom popisu.
     *
     * Usporeduje se bez obzira na velika slova: korisniku su "OPEN" i "open" ista sifra,
     * pa bi propustiti ih kao razlicite promasilo smisao pravila. Jedinstveno ogranicenje
     * u bazi hvata samo doslovno iste nizove, zato ova provjera stoji ispred njega.
     */
    private void rejectDuplicateCodes(List<SaveCodebookItemsRequest.CodebookItemInput> items) {
        Set<String> seen = new HashSet<>();
        for (SaveCodebookItemsRequest.CodebookItemInput input : items) {
            String code = input.code().trim().toLowerCase(Locale.ROOT);
            if (!seen.add(code)) {
                throw new DuplicateCodebookItemCodeException(input.code().trim());
            }
        }
    }

    /**
     * Stavka koja se koristi se ne brise.
     *
     * Mjerilo je uze nego kod brisanja cijelog sifrarnika: ondje smeta vec i sam stupac koji
     * na sifrarnik pokazuje, a ovdje tek stvarna upotreba SAME SIFRE. Stavku koja se nigdje
     * ne koristi nema razloga cuvati, a onu koja se koristi brisanje bi pretvorilo u sifru
     * bez znacenja - i to unatrag, u vec spremljenim podacima.
     *
     * Savjet u poruci ovisi o tome GDJE je sifra nadena, i to nije kozmetika:
     *   - upisana u zapis -> iskljuci stavku; stari zapisi ostaju citljivi, nova se ne nudi
     *   - zadana vrijednost stupca -> iskljucivanje NE pomaze (stupac bi i dalje
     *     predpopunjavao iskljucenu stavku, pa bi prvo sljedece uredivanje tog stupca palo),
     *     nego je treba maknuti sa stupca
     */
    private void requireNotInUse(Codebook codebook, List<CodebookItem> removed) {
        Set<String> codes = removed.stream().map(CodebookItem::getCode).collect(Collectors.toSet());
        CodebookUsage.CodeUse inUse = codebookUsage.firstCodeInUse(codebook, codes);
        if (inUse == null) {
            return;
        }
        throw new CodebookInUseException(inUse.asDefaultValue()
                ? "Stavka se ne može obrisati jer je zadana vrijednost stupca: " + inUse.where()
                        + ". Prvo maknite zadanu vrijednost s tog stupca."
                : "Stavka se ne može obrisati jer je već upisana u zapise: " + inUse.where()
                        + ". Isključite je umjesto brisanja - tada se više ne nudi za nove unose, "
                        + "a stari zapisi ostaju čitljivi.");
    }
}
