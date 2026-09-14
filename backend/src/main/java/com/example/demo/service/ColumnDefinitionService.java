package com.example.demo.service;

import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.dto.CreateColumnDefinitionRequest;
import com.example.demo.dto.ReorderColumnsRequest;
import com.example.demo.dto.UpdateColumnDefinitionRequest;
import com.example.demo.exception.ColumnDataLossException;
import com.example.demo.exception.DuplicateColumnKeyException;
import com.example.demo.exception.InvalidColumnDefinitionException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookScope;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.ColumnOptions;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.repository.SurveyResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Stupci jednog templatea (obrasca) - cuvaju se kao JSON niz u {@link Template}.
 *
 * Svaka izmjena je citanje-izmjena-pisanje cijelog templatea; provjere koje je
 * prije radila baza (jedinstvo naziva) rade ove metode. Vlasnistvo (pripada li
 * template firmi iz konteksta) uvijek prvo provjeri {@link TemplateService} -
 * tudi ili nepostojeci template zavrsi kao 404.
 *
 * Citanje ide kroz {@code requireOwned} (shemu treba svatko tko unosi zapise), a
 * svaka izmjena kroz {@code requireEditable}, koji uz vlasnistvo trazi i
 * administratorsku ulogu: shema je konfiguracija, a ne podatak.
 *
 * Globalnih stupaca vise nema: svaki template je neovisan, pa je otpala sva
 * logika oko "vrijedi za sve firme".
 */
@Service
public class ColumnDefinitionService {
    private static final Logger log = LoggerFactory.getLogger(ColumnDefinitionService.class);

    // Tipovi koje shema poznaje; sve izvan ovoga backend odbija (isto pravilo kao Editor).
    // "select" (ugraden popis vrijednosti) i "boolean" (Da/Ne) su maknuti - oboje su sada
    // stupac tipa "codebook" koji pokazuje na sifrarnik, pa dopusteni popis zivi na jednom
    // mjestu i dijeli ga vise obrazaca umjesto da se prepisuje u svaki stupac.
    private static final List<String> ALLOWED_TYPES =
            List.of("string", "number", "codebook", "date", "formula", "button", "file", "reference", "link");

    // Stupci koji u zapisu ne drze nikakvu vrijednost. Gumb nema sto spremiti, a prilozene
    // datoteke zive u vlastitoj tablici - upisati ih i u jsonb znacilo bi dva izvora istine
    // koji se mogu raziici.
    private static final List<String> VALUELESS_TYPES = List.of("button", "file");

    /**
     * Tipovi ciju celiju predstavlja GUMB, pa im natpis dolazi iz sheme.
     *
     * "link" je ovdje iako vrijednost ima: popis poveznica se ne ispisuje u celiju nego se
     * otvara u dijalogu, pa je i ondje jedino sto se vidi - gumb.
     */
    private static final List<String> BUTTON_LABEL_TYPES = List.of("button", "file", "link");

    /** Nacini odabira iz sifrarnika. */
    private static final List<String> PICKER_MODES = List.of("dropdown", "checkbox", "popup");

    /**
     * Sto gumb smije raditi: povijest retka, zakljucavanje ili povezani zapisi.
     *
     * Popis stoji ovdje, a ne kao enum, iz istog razloga kao {@link #ALLOWED_TYPES}: postavke
     * stupca zive u jsonb dokumentu, pa im vrijednosti moraju prezivjeti citanje starijih
     * zapisa u kojima ih jos nije bilo.
     */
    private static final List<String> BUTTON_ACTIONS =
            List.of(ColumnOptions.ACTION_HISTORY, ColumnOptions.ACTION_LOCK, ColumnOptions.ACTION_RELATED);

    /** Sto se dogada s vezama kad se ciljani zapis brise. */
    private static final List<String> ON_DELETE_MODES =
            List.of(ColumnOptions.ON_DELETE_RESTRICT, ColumnOptions.ON_DELETE_CLEAR);

    /**
     * Tipovi koji smiju sluziti kao NAZIV ciljanog zapisa u referentnom stupcu.
     *
     * Referenca kao naziv reference je izostavljena namjerno: naziv bi se tada razrjesavao u
     * lancu (A pokazuje na B, B na C...), pa bi ispis jedne celije trazio niz dohvata i mogao
     * bi se vrtjeti u krug. Gumb i datoteka otpadaju jer u zapisu ne drze nikakvu vrijednost.
     */
    private static final List<String> DISPLAY_COLUMN_TYPES =
            List.of("string", "number", "date", "codebook", "formula");

    // Uzorak koji korisnik upise izvrsava se na serveru, a dovoljno zamrsen regularni izraz
    // zna zaglaviti dretvu na ulazu koji mu ne odgovara. Duljina nije potpuna zastita, ali
    // odsijeca izraze koji se ne pisu slucajno.
    private static final int MAX_PATTERN_LENGTH = 200;

    private final TemplateService templateService;
    private final SurveyResultRepository surveyRepository;
    private final CodebookService codebookService;
    private final SchemaValidator validator;
    private final FormulaEvaluator formulaEvaluator;
    private final AttachmentCleanup attachmentCleanup;
    private final LogService logService;
    private final RecordChangeService recordChangeService;

    public ColumnDefinitionService(TemplateService templateService,
                                   SurveyResultRepository surveyRepository,
                                   CodebookService codebookService,
                                   SchemaValidator validator,
                                   FormulaEvaluator formulaEvaluator,
                                   AttachmentCleanup attachmentCleanup,
                                   LogService logService,
                                   RecordChangeService recordChangeService) {
        this.templateService = templateService;
        this.surveyRepository = surveyRepository;
        this.codebookService = codebookService;
        this.validator = validator;
        this.formulaEvaluator = formulaEvaluator;
        this.attachmentCleanup = attachmentCleanup;
        this.logService = logService;
        this.recordChangeService = recordChangeService;
    }

    /** Stupci templatea, onako kako stoje u bazi. */
    public List<ColumnDefinitionResponse> getForTemplate(Long templateId) {
        return entries(templateService.requireOwned(templateId)).stream()
                .map(ColumnDefinitionResponse::from)
                .toList();
    }

    @Transactional
    public ColumnDefinitionResponse create(Long templateId, CreateColumnDefinitionRequest request) {
        Template template = templateService.requireEditable(templateId);
        if (declares(template, request.columnKey())) {
            throw new DuplicateColumnKeyException(request.columnKey(), templateId);
        }

        ColumnEntry entry = normalize(new ColumnEntry(request.columnKey(), request.columnType(), request.codebookId(),
                request.label(), request.required(), request.unique(), request.defaultValue(), request.readOnly(),
                request.width(), request.options()));
        validateDefinition(entry, template, null);
        // provjera duplikata je ovdje suvisna: stupac je nov, a brisanje stupca cisti
        // njegove vrijednosti iz zapisa - pod ovim kljucem nista ne moze biti upisano
        addEntry(template, entry);
        templateService.save(template);

        // Nov stupac s formulom zatice zapise koji su nastali prije njega. Bez ovoga bi u
        // njima ostao prazan sve dok se svaki ne otvori i spremi rukom - a formula je upravo
        // ono sto nitko ne unosi rucno.
        int recomputed = recomputeFormulas(templateId, template, entry.type());
        log.info("Added column key={} templateId={} (recomputed {} record(s))",
                entry.key(), templateId, recomputed);
        logService.record("COLUMN_ADDED", describeColumn(entry) + " u obrazac id=" + templateId
                + (recomputed > 0 ? " (preračunato " + recomputed + " zapis(a))" : ""));
        return ColumnDefinitionResponse.from(entry);
    }

    /**
     * Izmjena postojeceg stupca uz migraciju zapisa istog templatea:
     *   - preimenovanje (novi naziv) -> kljuc se u svim zapisima preimenuje
     *   - promjena tipa/vrijednosti  -> vrijednost koja novom tipu ne odgovara se cisti
     *
     * Sve u jednoj transakciji da izmjena sheme i migracija podataka ne mogu stati
     * na pola i ostaviti nesklad.
     */
    @Transactional
    public ColumnDefinitionResponse update(Long templateId, String oldKey, UpdateColumnDefinitionRequest request) {
        Template template = templateService.requireEditable(templateId);
        boolean renamed = !oldKey.equals(request.columnKey());
        if (renamed && declares(template, request.columnKey())) {
            throw new DuplicateColumnKeyException(request.columnKey(), templateId);
        }

        // staro stanje se uhvati prije izmjene, da audit moze pokazati sto -> sto
        ColumnEntry before = findEntry(template, oldKey);
        if (before == null) {
            throw new ResourceNotFoundException(missingColumnMessage(oldKey, templateId));
        }
        ColumnEntry updatedEntry = normalize(new ColumnEntry(request.columnKey(), request.columnType(),
                request.codebookId(), request.label(), request.required(), request.unique(), request.defaultValue(),
                request.readOnly(), request.width(), request.options()));
        validateDefinition(updatedEntry, template, oldKey);
        if (updatedEntry.unique() && !before.unique()) {
            rejectIfAlreadyDuplicated(templateId, oldKey, updatedEntry);
        }
        // Provjera PRIJE spremanja: izmjena koja zapisima uzima vrijednosti mora biti
        // izricito potvrdena. Do sada su "promijenio sam uzorak" i "120 zapisa je ostalo bez
        // vrijednosti" bili isti klik, bez ijednog pitanja i bez traga u povijesti.
        requireConfirmedDataLoss(templateId, oldKey, updatedEntry, request.confirmDataLoss());

        replaceEntry(template, oldKey, updatedEntry);
        // Formule koje se pozivaju na stari naziv moraju ga slijediti - vidi rewriteFormulas.
        int rewritten = rewriteFormulas(template, oldKey, updatedEntry.key());
        templateService.save(template);

        migrateAttachments(templateId, oldKey, before, updatedEntry);
        int migrated = migrateColumn(templateId, oldKey, updatedEntry);
        // Izmijenjena formula vrijedi i unatrag - vidi recomputeFormulas. Gleda se i STARI tip:
        // stupac koji je prestao biti formula i dalje moze biti onaj na koji se druge formule
        // pozivaju, pa se i tada mora preracunati.
        int recomputed = recomputeFormulas(templateId, template, before.type(), updatedEntry.type());
        log.info("Updated column {} -> {} in templateId={} (migrated {} record(s), recomputed {})",
                oldKey, request.columnKey(), templateId, migrated, recomputed);
        logService.record("COLUMN_UPDATED", describeColumnChange(before, updatedEntry)
                + " u obrascu id=" + templateId + " (migrirano " + migrated + " zapis(a)"
                + (rewritten > 0 ? ", prepisano " + rewritten + " formul(a)" : "")
                + (recomputed > 0 ? ", preračunato " + recomputed : "") + ")");
        return ColumnDefinitionResponse.from(updatedEntry);
    }

    /**
     * Nov redoslijed stupaca. Ne dira nijedan zapis - poredak stupaca je stvar PRIKAZA.
     *
     * Redoslijed je vec i dosad odlucivao kako tablica izgleda (stupci se crtaju redom kojim
     * stoje u shemi), ali se mogao mijenjati samo brisanjem i ponovnim dodavanjem stupca - a to
     * brise podatke. Ovime se mijenja sam popis, i nista vise.
     *
     * Salju se svi kljucevi odjednom, pa se moze provjeriti da je skup ostao isti; vidi
     * {@link ReorderColumnsRequest}.
     */
    @Transactional
    public List<ColumnDefinitionResponse> reorder(Long templateId, ReorderColumnsRequest request) {
        Template template = templateService.requireEditable(templateId);
        List<ColumnEntry> current = entries(template);

        List<String> wanted = request.columnKeys();
        if (new LinkedHashSet<>(wanted).size() != wanted.size()) {
            throw new InvalidColumnDefinitionException("Redoslijed sadrži isti stupac dvaput.");
        }
        Map<String, ColumnEntry> byKey = new LinkedHashMap<>();
        for (ColumnEntry entry : current) {
            byKey.put(entry.key(), entry);
        }
        // Skup mora biti isti - ni manjka ni viska. Manjak bi znacio da bi stupac ispao iz
        // sheme (a s njim bi se, pri sljedecem citanju, izgubio i pristup vrijednostima koje
        // zapisi jos drze), visak da se poziva na stupac kojeg nema.
        if (wanted.size() != byKey.size() || !byKey.keySet().containsAll(wanted)) {
            throw new InvalidColumnDefinitionException(
                    "Redoslijed se ne poklapa sa stupcima obrasca - osvježite stranicu i pokušajte ponovno.");
        }

        List<ColumnEntry> reordered = new ArrayList<>();
        for (String key : wanted) {
            reordered.add(byKey.get(key));
        }
        template.setDefinitions(reordered);
        templateService.save(template);

        log.info("Reordered {} column(s) in templateId={}", reordered.size(), templateId);
        logService.record("COLUMNS_REORDERED", "Redoslijed stupaca obrasca id=" + templateId
                + ": " + String.join(", ", wanted));
        return reordered.stream().map(ColumnDefinitionResponse::from).toList();
    }

    /** Makne stupac iz templatea i pobrise vrijednosti tog kljuca iz njegovih zapisa. */
    @Transactional
    public void delete(Long templateId, String columnKey) {
        Template template = templateService.requireEditable(templateId);
        if (!removeEntry(template, columnKey)) {
            throw new ResourceNotFoundException(missingColumnMessage(columnKey, templateId));
        }
        templateService.save(template);

        int cleared = stripFromSurveys(templateId, columnKey);
        // prilozi ne zive u jsonb-u, pa ih stripFromSurveys ne bi ni vidio
        int attachments = attachmentCleanup.forColumn(templateId, columnKey);
        log.info("Removed column key={} from templateId={} (cleared from {} record(s), {} attachment(s))",
                columnKey, templateId, cleared, attachments);
        logService.record("COLUMN_REMOVED", "Stupac \"" + columnKey + "\" iz obrasca id=" + templateId
                + " (očišćeno " + cleared + " zapis(a)"
                + (attachments > 0 ? ", obrisano " + attachments + " prilog(a)" : "") + ")");
    }

    // --- pomocne metode ---

    /**
     * Prilozi prate izmjenu stupca: preimenovanje ih premjesta na novi naziv, a prelazak na
     * drugi tip ih brise.
     *
     * Brisanje pri promjeni tipa je ista odluka kao ciscenje vrijednosti koje novom tipu ne
     * odgovaraju: stupac koji vise nije "file" nema gdje prikazati priloge, pa bi oni ostali
     * u bazi bez ijednog puta do njih - ni za citanje ni za brisanje.
     */
    private void migrateAttachments(Long templateId, String oldKey, ColumnEntry before, ColumnEntry after) {
        if (!"file".equals(before.type())) {
            return;
        }
        if ("file".equals(after.type())) {
            attachmentCleanup.renameColumn(templateId, oldKey, after.key());
        } else {
            attachmentCleanup.forColumn(templateId, oldKey);
        }
    }

    /**
     * Stupac doveden u oblik u kojem se sprema.
     *
     * Dvije postavke same po sebi znace "korisnik ovo polje ne unosi", pa se {@code readOnly}
     * na njima nameće umjesto da se traži od onoga tko definira stupac: automatski redni broj
     * i formulu jednako racuna server. Odbiti takav stupac bilo bi tocno, ali bi znacilo
     * grestu na nesto sto nema drugog ispravnog odgovora.
     */
    private ColumnEntry normalize(ColumnEntry entry) {
        boolean serverComputed = "formula".equals(entry.type()) || entry.options().autoIncrement();
        if (!serverComputed || entry.readOnly()) {
            return entry;
        }
        return new ColumnEntry(entry.key(), entry.type(), entry.codebookId(), entry.label(), entry.required(),
                entry.unique(), entry.defaultValue(), true, entry.width(), entry.options());
    }

    /**
     * Pravila definicije stupca (isto sto brani i Editor). Uz tip i sifrarnik ovdje se
     * odbijaju i kombinacije zastavica koje se same sa sobom ne slazu - bolje ih je
     * zaustaviti pri deklariranju sheme nego dopustiti stupac u koji se poslije nista ne moze upisati.
     *
     * @param template    obrazac u koji stupac ide - treba formuli, koja se poziva na druge stupce
     * @param replacedKey stupac koji se ovime mijenja, ili null kod novog. Formula ga preskace
     *                    pri trazenju kruzne ovisnosti, jer ga vise nece biti.
     */
    private void validateDefinition(ColumnEntry entry, Template template, String replacedKey) {
        String columnType = entry.type();
        if (!ALLOWED_TYPES.contains(columnType)) {
            throw new InvalidColumnDefinitionException("Nepoznat tip stupca \"" + columnType
                    + "\". Dozvoljeni tipovi: " + String.join(", ", ALLOWED_TYPES) + ".");
        }
        requireCodebookMatchesType(entry);
        requireOptionsMatchType(entry);
        requireWidthSane(entry);
        validateNumberOptions(entry);
        validatePattern(entry);
        validateCodebookOptions(entry);
        validateReferenceOptions(entry);
        validateFormula(entry, template, replacedKey);

        // zadana vrijednost se unosi kao tekst, ali mora odgovarati tipu - inace bi forma
        // predpopunila upravo ono sto backend pri spremanju odbija
        String defaultProblem = validator.defaultValueProblem(ColumnDefinitionResponse.from(entry));
        if (defaultProblem != null) {
            throw new InvalidColumnDefinitionException("Zadana vrijednost ne odgovara stupcu: " + defaultProblem + ".");
        }

        if (entry.unique() && hasDefault(entry)) {
            throw new InvalidColumnDefinitionException(
                    "Jedinstven stupac ne može imati zadanu vrijednost - drugi zapis bi odmah bio duplikat.");
        }
        // automatski redni broj i formula su readOnly, ali vrijednost NE dolazi iz zadane
        // vrijednosti nego je racuna server - njima obaveznost ne treba nista predpopunjeno
        if (entry.required() && entry.readOnly() && !hasDefault(entry) && !isServerComputed(entry)) {
            throw new InvalidColumnDefinitionException(
                    "Obavezan stupac koji se ne može uređivati mora imati zadanu vrijednost.");
        }
    }

    private boolean hasDefault(ColumnEntry entry) {
        return entry.defaultValue() != null && !entry.defaultValue().isBlank();
    }

    /** Stupac cija vrijednost nastaje na serveru, a ne unosom. */
    private boolean isServerComputed(ColumnEntry entry) {
        return "formula".equals(entry.type()) || entry.options().autoIncrement();
    }

    /**
     * Postavka koja se odabranom tipu ne tice se odbija umjesto da se tiho zanemari.
     *
     * Razlog nije urednost: takva bi postavka ostala u shemi kao tiha neistina - stupac bi
     * tvrdio da ima raspon ili uzorak, a nista ga ne bi provodilo. Najgore od svega,
     * ponovno bi ozivjela ako se stupcu jednom promijeni tip.
     */
    private void requireOptionsMatchType(ColumnEntry entry) {
        ColumnOptions options = entry.options();
        String type = entry.type();

        rejectUnless("number".equals(type), options.autoIncrement(), "Automatski redni broj", type);
        rejectUnless("number".equals(type), options.numberMode() != null, "Način prikaza broja", type);
        rejectUnless("number".equals(type), options.min() != null, "Najmanja vrijednost", type);
        rejectUnless("number".equals(type), options.max() != null, "Najveća vrijednost", type);
        rejectUnless("number".equals(type), notBlank(options.numberFormat()), "Format broja", type);
        rejectUnless("number".equals(type) || "string".equals(type), notBlank(options.pattern()), "Uzorak", type);
        rejectUnless("date".equals(type), options.dateMode() != null, "Format datuma", type);
        // Nacin odabira ostaje samo sifrarnicki: referenca se UVIJEK bira kroz dijalog s
        // pretragom, pa bi postavka "padajuci izbornik" bila tiha neistina - stupac bi tvrdio
        // nesto sto ga nista ne bi natjeralo da radi.
        rejectUnless("codebook".equals(type), options.pickerMode() != null, "Način odabira", type);
        rejectUnless("codebook".equals(type) || "reference".equals(type), options.multiple(),
                "Višestruki odabir", type);
        rejectUnless("codebook".equals(type), options.allowNewValues(), "Slobodan unos", type);
        rejectUnless("reference".equals(type), options.targetTemplateId() != null, "Povezani obrazac", type);
        rejectUnless("reference".equals(type), notBlank(options.displayColumnKey()), "Stupac za naziv", type);
        rejectUnless("reference".equals(type), notBlank(options.onTargetDelete()),
                "Ponašanje pri brisanju", type);
        rejectUnless("formula".equals(type), notBlank(options.formula()), "Formula", type);
        // natpis dijele svi stupci cija je celija gumb - vidi BUTTON_LABEL_TYPES
        rejectUnless(BUTTON_LABEL_TYPES.contains(type), notBlank(options.buttonLabel()), "Natpis na gumbu", type);
        // radnja je samo gumbova: stupac za prilaganje vec ima svoju (otvara odabir datoteke)
        rejectUnless("button".equals(type), notBlank(options.buttonAction()), "Radnja gumba", type);

        // Gumb bez radnje je prazan drzac mjesta - tocno ono sto je do sada i bio. Sada kad
        // radnja postoji, izostavljanje je greska pri unosu, a ne zatecena praznina.
        if ("button".equals(type) && !notBlank(options.buttonAction())) {
            throw new InvalidColumnDefinitionException("Odaberite što gumb radi.");
        }
        if ("button".equals(type) && !BUTTON_ACTIONS.contains(options.buttonAction())) {
            throw new InvalidColumnDefinitionException(
                    "Nepoznata radnja gumba: \"" + options.buttonAction() + "\".");
        }
        // stupci bez vlastite vrijednosti: pravila o sadrzaju nad njima nemaju o cemu govoriti
        if (VALUELESS_TYPES.contains(type) && (entry.required() || entry.unique() || hasDefault(entry))) {
            throw new InvalidColumnDefinitionException("Stupac tipa \"" + typeLabel(type)
                    + "\" ne sprema vrijednost, pa ne može biti obavezan, jedinstven ni imati zadanu vrijednost.");
        }
        // Poveznica je popis, a ne jedna vrijednost - zato ista pravila kao za visestruki
        // odabir. "Obavezan" ostaje smislen i znaci "barem jedna poveznica".
        if ("link".equals(type) && (entry.unique() || hasDefault(entry))) {
            throw new InvalidColumnDefinitionException(
                    "Stupac s poveznicama je popis, pa ne može biti jedinstven ni imati zadanu vrijednost.");
        }
        if ("formula".equals(type) && hasDefault(entry)) {
            throw new InvalidColumnDefinitionException(
                    "Stupac s formulom ne može imati zadanu vrijednost - vrijednost se računa pri svakom spremanju.");
        }
    }

    private void rejectUnless(boolean allowedType, boolean isSet, String what, String type) {
        if (isSet && !allowedType) {
            throw new InvalidColumnDefinitionException(
                    what + " se ne može postaviti na stupcu tipa \"" + typeLabel(type) + "\".");
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Sirina je stvar prikaza, pa se ne provjerava strogo - samo se odbijaju vrijednosti
     * kod kojih se stupac vise ne bi mogao ni procitati ni dohvatiti mišem.
     */
    private void requireWidthSane(ColumnEntry entry) {
        Integer width = entry.width();
        if (width != null && (width < 40 || width > 2000)) {
            throw new InvalidColumnDefinitionException("Širina stupca mora biti između 40 i 2000 piksela.");
        }
    }

    private void validateNumberOptions(ColumnEntry entry) {
        ColumnOptions options = entry.options();
        if (options.numberMode() != null && !List.of("int", "float").contains(options.numberMode())) {
            throw new InvalidColumnDefinitionException("Način prikaza broja mora biti \"int\" ili \"float\".");
        }
        if (options.min() != null && options.max() != null && options.min().compareTo(options.max()) > 0) {
            throw new InvalidColumnDefinitionException("Najmanja vrijednost ne može biti veća od najveće.");
        }
        if (options.isInteger() && hasFraction(options.min())) {
            throw new InvalidColumnDefinitionException("Najmanja vrijednost mora biti cijeli broj.");
        }
        if (options.isInteger() && hasFraction(options.max())) {
            throw new InvalidColumnDefinitionException("Najveća vrijednost mora biti cijeli broj.");
        }
        if (!options.autoIncrement()) {
            return;
        }
        // brojac dodjeljuje vrijednost, pa je sve sto bi je unaprijed odredilo proturjecno
        if (hasDefault(entry)) {
            throw new InvalidColumnDefinitionException(
                    "Stupac s automatskim rednim brojem ne može imati zadanu vrijednost - broj dodjeljuje server.");
        }
        if (!options.isInteger()) {
            throw new InvalidColumnDefinitionException(
                    "Automatski redni broj mora biti cijeli broj - postavite način prikaza na Int.");
        }
    }

    private boolean hasFraction(java.math.BigDecimal value) {
        return value != null && value.stripTrailingZeros().scale() > 0;
    }

    /**
     * Uzorak mora biti ispravan regularni izraz.
     *
     * Neispravan bi se pri upisu retka tiho preskakao (vidi {@code SchemaValidator}), pa bi
     * stupac izgledao kao da ima provjeru koja se nikad ne ukljucuje - a to je gore od
     * stupca koji je nema.
     */
    private void validatePattern(ColumnEntry entry) {
        String pattern = entry.options().pattern();
        if (!notBlank(pattern)) {
            return;
        }
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            throw new InvalidColumnDefinitionException(
                    "Uzorak je predug (najviše " + MAX_PATTERN_LENGTH + " znakova).");
        }
        try {
            java.util.regex.Pattern.compile(pattern);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new InvalidColumnDefinitionException("Uzorak nije ispravan regularni izraz: " + e.getDescription() + ".");
        }
    }

    private void validateCodebookOptions(ColumnEntry entry) {
        ColumnOptions options = entry.options();
        if (!"codebook".equals(entry.type())) {
            return;
        }
        if (options.pickerMode() != null && !PICKER_MODES.contains(options.pickerMode())) {
            throw new InvalidColumnDefinitionException(
                    "Način odabira mora biti jedan od: " + String.join(", ", PICKER_MODES) + ".");
        }
        if (options.multiple()) {
            // Padajuci izbornik s vise odabranih je kontrola koju gotovo nitko ne zna koristiti;
            // za vise vrijednosti postoje potvrdni okviri i dijalog. Trazi se izricit odabir, a
            // ne se oslanja na zadano - stupac koji drzi popis mora reci kako ga prikazuje.
            if (!"checkbox".equals(options.pickerMode()) && !"popup".equals(options.pickerMode())) {
                throw new InvalidColumnDefinitionException(
                        "Višestruki odabir se prikazuje potvrdnim okvirima ili dijalogom, ne padajućim izbornikom.");
            }
            if (entry.unique()) {
                throw new InvalidColumnDefinitionException(
                        "Stupac s višestrukim odabirom ne može biti jedinstven.");
            }
        }
        if (options.allowNewValues()) {
            requireCodebookGrowable(entry.codebookId());
        }
    }

    /**
     * Referentni stupac: ciljani obrazac, stupac koji sluzi kao naziv i ponasanje pri brisanju.
     *
     * Ciljani obrazac se trazi kroz {@link TemplateService#requireOwned} - tudi ili nepostojeci
     * zavrsava kao 404, jednako kao svugdje. Bez toga bi se veza mogla uperiti u obrazac druge
     * firme i time probiti izolaciju na najtisi moguci nacin: kroz shemu, a ne kroz podatke.
     *
     * Obrazac koji pokazuje SAM NA SEBE je dopusten - hijerarhija (proces -> nadredeni proces)
     * je trazena, a ne greska. Zapis koji pokazuje sam na sebe se odbija drugdje
     * ({@code SurveyResultService}), jer je to pitanje podataka, ne sheme.
     */
    private void validateReferenceOptions(ColumnEntry entry) {
        if (!"reference".equals(entry.type())) {
            return;
        }
        ColumnOptions options = entry.options();
        if (options.targetTemplateId() == null) {
            throw new InvalidColumnDefinitionException("Stupac s vezom mora imati odabran povezani obrazac.");
        }
        Template target = templateService.requireOwned(options.targetTemplateId());

        if (!notBlank(options.displayColumnKey())) {
            throw new InvalidColumnDefinitionException(
                    "Odaberite koji se stupac obrasca \"" + target.getName() + "\" prikazuje umjesto broja zapisa.");
        }
        ColumnEntry display = findEntry(target, options.displayColumnKey());
        if (display == null) {
            throw new InvalidColumnDefinitionException("Obrazac \"" + target.getName()
                    + "\" nema stupac \"" + options.displayColumnKey() + "\".");
        }
        if (!DISPLAY_COLUMN_TYPES.contains(display.type())) {
            throw new InvalidColumnDefinitionException("Stupac \"" + display.displayLabel()
                    + "\" ne može služiti kao naziv zapisa (tip: " + typeLabel(display.type()) + ").");
        }
        if (options.onTargetDelete() != null && !ON_DELETE_MODES.contains(options.onTargetDelete())) {
            throw new InvalidColumnDefinitionException(
                    "Nepoznato ponašanje pri brisanju: \"" + options.onTargetDelete() + "\".");
        }
        if (hasDefault(entry)) {
            throw new InvalidColumnDefinitionException(
                    "Stupac s vezom ne može imati zadanu vrijednost - veza se bira po zapisu.");
        }
        if (options.multiple() && entry.unique()) {
            throw new InvalidColumnDefinitionException(
                    "Stupac s višestrukim odabirom ne može biti jedinstven.");
        }
    }

    /**
     * Sifrarnik koji se smije puniti kroz unos zapisa mora pripadati firmi.
     *
     * Globalni vrijedi za sve firme, pa bi ga jedan korisnik svojim unosom mijenjao svima -
     * i to bez ijedne administratorske ovlasti. Zabrana stoji ovdje, pri definiranju stupca,
     * da se do te situacije uopce ne dode; druga brava je u {@code CodebookItemService.ensureCode}.
     */
    private void requireCodebookGrowable(Long codebookId) {
        if (codebookId == null) {
            return;
        }
        Codebook codebook = codebookService.requireAccessible(codebookId);
        if (codebook.getScope() == CodebookScope.GLOBAL) {
            throw new InvalidColumnDefinitionException("Slobodan unos se ne može uključiti nad globalnim šifrarnikom \""
                    + codebook.getName() + "\" - unos jedne firme mijenjao bi popis svim firmama. Napravite šifrarnik firme.");
        }
    }

    /**
     * Formula mora biti citljiva, pozivati se na postojece brojcane stupce i ne vrtjeti se u krug.
     *
     * Sve troje se provjerava OVDJE, a ne pri spremanju zapisa: ondje je prekasno - korisnik
     * koji unosi podatke nije ni vidio formulu, a ne moze je ni popraviti.
     */
    private void validateFormula(ColumnEntry entry, Template template, String replacedKey) {
        if (!"formula".equals(entry.type())) {
            return;
        }
        String formula = entry.options().formula();
        List<String> referenced;
        try {
            referenced = formulaEvaluator.references(formula);
        } catch (IllegalArgumentException e) {
            throw new InvalidColumnDefinitionException("Formula nije ispravna: " + e.getMessage() + ".");
        }
        if (referenced.isEmpty()) {
            throw new InvalidColumnDefinitionException(
                    "Formula se ne poziva ni na jedan stupac. Stupac se navodi u uglatim zagradama, npr. [cijena] * [količina].");
        }

        Map<String, ColumnEntry> others = new LinkedHashMap<>();
        for (ColumnEntry other : entries(template)) {
            if (!other.key().equals(replacedKey)) {
                others.put(other.key(), other);
            }
        }
        for (String key : referenced) {
            if (key.equals(entry.key())) {
                throw new InvalidColumnDefinitionException("Formula se poziva sama na sebe.");
            }
            ColumnEntry referencedColumn = others.get(key);
            if (referencedColumn == null) {
                throw new InvalidColumnDefinitionException(
                        "Formula se poziva na stupac \"" + key + "\", kojeg u ovom obrascu nema.");
            }
            if (!"number".equals(referencedColumn.type()) && !"formula".equals(referencedColumn.type())) {
                throw new InvalidColumnDefinitionException("Formula se poziva na stupac \"" + key
                        + "\", koji nije broj (tip: " + typeLabel(referencedColumn.type()) + ").");
            }
        }

        others.put(entry.key(), entry);
        String cycle = findCycle(entry.key(), others, new LinkedHashSet<>());
        if (cycle != null) {
            throw new InvalidColumnDefinitionException("Formule se vrte u krug: " + cycle + ".");
        }
    }

    /**
     * Vodi li lanac poziva iz ovog stupca natrag u stupac koji je vec na putu.
     *
     * @param visiting stupci na trenutnom putu, redom - sluze i za otkrivanje kruga i za
     *                 njegov ispis, jer poruka "A -> B -> A" kaze puno vise od "kruzna ovisnost"
     * @return opis kruga, ili null ako ga nema
     */
    private String findCycle(String key, Map<String, ColumnEntry> byKey, LinkedHashSet<String> visiting) {
        if (!visiting.add(key)) {
            return String.join(" -> ", visiting) + " -> " + key;
        }
        ColumnEntry column = byKey.get(key);
        if (column != null && "formula".equals(column.type())) {
            List<String> referenced;
            try {
                referenced = formulaEvaluator.references(column.options().formula());
            } catch (IllegalArgumentException e) {
                referenced = List.of();
            }
            for (String next : referenced) {
                String cycle = findCycle(next, byKey, visiting);
                if (cycle != null) {
                    return cycle;
                }
            }
        }
        visiting.remove(key);
        return null;
    }

    /**
     * Sifrarnik i tip moraju ici zajedno: stupac tipa "codebook" bez sifrarnika ne bi imao
     * odakle uzeti dopustene vrijednosti, a sifrarnik na stupcu bilo kojeg drugog tipa nitko
     * ne bi citao - ostao bi kao tiha neistina u shemi.
     *
     * Postojanje i doseg sifrarnika provjerava {@link CodebookService#requireAccessible} -
     * ono sto firma ne smije vidjeti zavrsava kao 404, jednako kao da ne postoji. Firmin
     * obrazac zato smije koristiti svoj ili globalni sifrarnik, ali nikad tudi.
     */
    private void requireCodebookMatchesType(ColumnEntry entry) {
        boolean isCodebookColumn = "codebook".equals(entry.type());
        if (isCodebookColumn && entry.codebookId() == null) {
            throw new InvalidColumnDefinitionException("Stupac vezan na šifrarnik mora imati odabran šifrarnik.");
        }
        if (!isCodebookColumn && entry.codebookId() != null) {
            throw new InvalidColumnDefinitionException(
                    "Šifrarnik se može odabrati samo na stupcu tipa \"Iz šifrarnika\".");
        }
        if (isCodebookColumn) {
            codebookService.requireAccessible(entry.codebookId());
        }
    }

    /**
     * Ukljucivanje jedinstvenosti na stupac koji vec ima upisane vrijednosti ima smisla samo
     * ako su te vrijednosti vec razlicite. Inace bi shema tvrdila nesto sto podaci demantiraju,
     * a korisnik bi to otkrio tek kad mu prvo sljedece spremanje nepromijenjenog retka padne.
     *
     * Gledaju se vrijednosti STAROG kljuca (one koje ce migracijom zavrsiti pod novim), i to
     * samo one koje prezive novi tip - ostale ce migracija ionako ocistiti.
     */
    private void rejectIfAlreadyDuplicated(Long templateId, String oldKey, ColumnEntry newEntry) {
        ColumnDefinitionResponse newColumn = ColumnDefinitionResponse.from(newEntry);
        List<Object> values = new ArrayList<>();
        for (SurveyResult survey : surveyRepository.findByTemplateId(templateId)) {
            Map<String, Object> data = survey.getData();
            Object value = data == null ? null : data.get(oldKey);
            if (value != null && validator.fieldProblem(newColumn, value) == null) {
                values.add(value);
            }
        }
        Object duplicate = validator.findDuplicate(values);
        if (duplicate != null) {
            throw new InvalidColumnDefinitionException("Stupac se ne može označiti kao jedinstven: vrijednost \""
                    + duplicate + "\" već se pojavljuje u više zapisa.");
        }
    }

    /**
     * Prosetaj zapise templatea i prenesi vrijednost sa starog na novi kljuc,
     * zadrzavajuci samo ono sto novom stupcu odgovara.
     *
     * @return broj zapisa u kojima je nesto promijenjeno
     */
    private int migrateColumn(Long templateId, String oldKey, ColumnEntry newEntry) {
        ColumnDefinitionResponse newColumn = ColumnDefinitionResponse.from(newEntry);
        int migrated = 0;
        for (SurveyResult survey : surveyRepository.findByTemplateId(templateId)) {
            Map<String, Object> data = survey.getData();
            if (data == null || !data.containsKey(oldKey)) {
                continue;
            }
            Map<String, Object> updated = new HashMap<>(data);
            Object value = updated.remove(oldKey);
            // vrijednost ostaje (pod novim kljucem) samo ako prolazi novi tip; inace se cisti
            if (validator.fieldProblem(newColumn, value) == null) {
                updated.put(newEntry.key(), value);
            } else {
                // Izgubljena vrijednost ulazi u povijest retka. Bez ovoga je izmjena sheme
                // bila jedini nacin da podatak nestane BEZ TRAGA - a povijest koja presuti
                // brisanje ne dokazuje nista. Preimenovanje se ovdje ne biljezi: vrijednost
                // je ostala, samo pod drugim kljucem.
                recordChangeService.valueCleared(survey.getId(), templateId, survey.getCompanyId(),
                        oldKey, value);
            }
            survey.setData(updated);
            surveyRepository.save(survey);
            migrated++;
        }
        return migrated;
    }

    /**
     * Zaustavi izmjenu koja bi zapisima uzela vrijednosti, ako to nije izricito potvrdeno.
     *
     * Broji se ISTIM pravilom kojim {@link #migrateColumn} poslije cisti - inace bi broj u
     * pitanju i stvarni ucinak mogli biti razliciti, sto je gore od nikakvog pitanja.
     *
     * Preimenovanje samo po sebi nista ne gubi (vrijednost seli pod novi kljuc), pa se ovdje
     * ne racuna: gleda se odgovara li vrijednost NOVOJ definiciji, a ne mijenja li se naziv.
     */
    private void requireConfirmedDataLoss(Long templateId, String oldKey, ColumnEntry newEntry,
                                          boolean confirmed) {
        if (confirmed) {
            return;
        }
        ColumnDefinitionResponse newColumn = ColumnDefinitionResponse.from(newEntry);
        int affected = 0;
        for (SurveyResult survey : surveyRepository.findByTemplateId(templateId)) {
            Map<String, Object> data = survey.getData();
            if (data == null || !data.containsKey(oldKey)) {
                continue;
            }
            if (validator.fieldProblem(newColumn, data.get(oldKey)) != null) {
                affected++;
            }
        }
        if (affected == 0) {
            return;
        }
        throw new ColumnDataLossException("Ovom izmjenom " + affected
                + (affected == 1 ? " zapis ostaje" : " zapisa ostaje") + " bez vrijednosti stupca \""
                + oldKey + "\", jer njihova vrijednost ne odgovara novoj definiciji.", affected);
    }

    /**
     * Preimenovan stupac mora povuci i formule koje se na njega pozivaju.
     *
     * Bez ovoga se formula lomi TIHO: {@code [cijena] * [kolicina]} nakon preimenovanja
     * "kolicina" u "komada" i dalje trazi kljuc kojeg vise nema, pa se stupac pri sljedecem
     * spremanju zapisa jednostavno isprazni - bez ijedne poruke, i to danima poslije, kad
     * nitko vise ne povezuje jedno s drugim.
     *
     * Preimenovanje ionako migrira kljuceve u SVIM zapisima; formula je isti takav kljuc,
     * samo zapisan u shemi.
     *
     * @return broj prepisanih formula
     */
    private int rewriteFormulas(Template template, String oldKey, String newKey) {
        if (oldKey.equals(newKey) || template.getDefinitions() == null) {
            return 0;
        }
        List<ColumnEntry> definitions = template.getDefinitions();
        int rewritten = 0;
        for (int i = 0; i < definitions.size(); i++) {
            ColumnEntry entry = definitions.get(i);
            String formula = entry.options().formula();
            if (!"formula".equals(entry.type()) || !notBlank(formula)) {
                continue;
            }
            String updated = formulaEvaluator.rename(formula, oldKey, newKey);
            if (updated.equals(formula)) {
                continue;
            }
            definitions.set(i, entry.withOptions(entry.options().withFormula(updated)));
            rewritten++;
            log.info("Formula stupca {} prepisana: {} -> {}", entry.key(), formula, updated);
        }
        return rewritten;
    }

    /**
     * Preracunaj formule svih zapisa obrasca. Radi se samo kad je izmjena dirala FORMULU.
     *
     * Zasto uopce: formula se dosad racunala samo pri spremanju zapisa, pa je promjena formule
     * vrijedila tek unaprijed. Zapisi uneseni prije nje zadrzali su stari rezultat - stupac
     * "C" je tvrdio da je umnozak, a u njemu je stajao zbroj (ili obrnuto). To nije zastarjeli
     * prikaz nego NEISTINA u podacima: vrijednost koja ne odgovara pravilu koje je uz nju
     * napisano, i po kojoj ce netko donijeti odluku.
     *
     * Racunaju se SVE formule obrasca, ne samo izmijenjena: formule se smiju pozivati jedna na
     * drugu ({@code D = [C] + 1}), pa promjena jedne mijenja i sve koje o njoj ovise.
     * {@code computeAll} ih ionako slaze u ispravan redoslijed.
     *
     * Promjena ulazi u povijest zapisa kao i svaka druga - iz cega u sto - jer ce se prije ili
     * kasnije netko pitati zasto je broj drugaciji nego jucer.
     *
     * @param touched tipovi stupca prije i poslije izmjene; ako medu njima nema formule,
     *                izmjena rezultat ne moze promijeniti i ne dira se nijedan zapis
     * @return broj zapisa kojima se vrijednost stvarno promijenila
     */
    private int recomputeFormulas(Long templateId, Template template, String... touched) {
        if (Arrays.stream(touched).noneMatch("formula"::equals)) {
            return 0;
        }
        List<ColumnDefinitionResponse> schema = entries(template).stream()
                .map(ColumnDefinitionResponse::from)
                .toList();
        if (schema.stream().noneMatch(column -> "formula".equals(column.columnType()))) {
            return 0;
        }

        int changed = 0;
        for (SurveyResult survey : surveyRepository.findByTemplateId(templateId)) {
            Map<String, Object> data = survey.getData() == null ? Map.of() : survey.getData();
            Map<String, Object> computed = formulaEvaluator.computeAll(schema, data);
            if (computed.equals(data)) {
                continue;
            }
            recordChangeService.capture(survey.getId(), templateId, survey.getCompanyId(), data, computed);
            survey.setData(new HashMap<>(computed));
            surveyRepository.save(survey);
            changed++;
        }
        return changed;
    }

    /** @return broj zapisa iz kojih je vrijednost stvarno maknuta */
    private int stripFromSurveys(Long templateId, String columnKey) {
        int cleared = 0;
        for (SurveyResult survey : surveyRepository.findByTemplateId(templateId)) {
            Map<String, Object> data = survey.getData();
            if (data == null || !data.containsKey(columnKey)) {
                continue;
            }
            Map<String, Object> withoutColumn = new HashMap<>(data);
            Object value = withoutColumn.remove(columnKey);
            // brisanje stupca je za pojedini zapis isto sto i izgubljena vrijednost
            recordChangeService.valueCleared(survey.getId(), templateId, survey.getCompanyId(),
                    columnKey, value);
            survey.setData(withoutColumn);
            surveyRepository.save(survey);
            cleared++;
        }
        return cleared;
    }

    // Uvijek novi popis umjesto izmjene postojeceg: kod jsonb stupca Hibernate
    // ne primijeti pouzdano da je sadrzaj liste promijenjen u mjestu.
    private void addEntry(Template template, ColumnEntry entry) {
        List<ColumnEntry> updated = new ArrayList<>(entries(template));
        updated.add(entry);
        template.setDefinitions(updated);
    }

    /** Zamijeni stupac zadrzavajuci mu poziciju. @return je li stari stupac postojao */
    private boolean replaceEntry(Template template, String oldKey, ColumnEntry newEntry) {
        List<ColumnEntry> updated = new ArrayList<>();
        boolean replaced = false;
        for (ColumnEntry entry : entries(template)) {
            if (entry.key().equals(oldKey)) {
                updated.add(newEntry);
                replaced = true;
            } else {
                updated.add(entry);
            }
        }
        if (replaced) {
            template.setDefinitions(updated);
        }
        return replaced;
    }

    private boolean removeEntry(Template template, String columnKey) {
        List<ColumnEntry> remaining = new ArrayList<>();
        for (ColumnEntry entry : entries(template)) {
            if (!entry.key().equals(columnKey)) {
                remaining.add(entry);
            }
        }
        if (remaining.size() == entries(template).size()) {
            return false;
        }
        template.setDefinitions(remaining);
        return true;
    }

    private boolean declares(Template template, String columnKey) {
        return entries(template).stream().anyMatch(entry -> entry.key().equals(columnKey));
    }

    private ColumnEntry findEntry(Template template, String columnKey) {
        return entries(template).stream()
                .filter(entry -> entry.key().equals(columnKey))
                .findFirst()
                .orElse(null);
    }

    private List<ColumnEntry> entries(Template template) {
        return template.getDefinitions() != null ? template.getDefinitions() : List.of();
    }

    private String missingColumnMessage(String columnKey, Long templateId) {
        return "Column \"" + columnKey + "\" not found in template " + templateId;
    }

    /** Opis jednog stupca za audit: naziv, tip, sifrarnik i pravila unosa. */
    private String describeColumn(ColumnEntry entry) {
        StringBuilder sb = new StringBuilder("Stupac \"" + entry.key() + "\" (tip: " + typeLabel(entry.type()));
        if (entry.codebookId() != null) {
            sb.append("; šifrarnik: ").append(codebookLabel(entry.codebookId()));
        }
        if (!entry.displayLabel().equals(entry.key())) {
            sb.append("; naziv: ").append(entry.displayLabel());
        }
        if (hasDefault(entry)) {
            sb.append("; zadano: ").append(entry.defaultValue());
        }
        String flags = describeFlags(entry);
        if (!flags.isEmpty()) {
            sb.append("; ").append(flags);
        }
        return sb.append(")").toString();
    }

    /**
     * Ukljucene zastavice i postavke, nabrojene samo kad ih ima - "stupac bez posebnosti"
     * nema sto reci.
     *
     * Nabraja se ono sto mijenja PONASANJE stupca (sto se smije upisati, tko upisuje), a ne
     * ono sto mijenja izgled: sirina i format broja u dnevniku bi bili sum.
     */
    private String describeFlags(ColumnEntry entry) {
        ColumnOptions options = entry.options();
        List<String> flags = new ArrayList<>();
        if (entry.required()) {
            flags.add("obavezno");
        }
        if (entry.unique()) {
            flags.add("jedinstveno");
        }
        if (entry.readOnly()) {
            flags.add("samo za čitanje");
        }
        if (options.autoIncrement()) {
            flags.add("automatski redni broj");
        }
        if (options.multiple()) {
            flags.add("višestruki odabir");
        }
        if (options.allowNewValues()) {
            flags.add("slobodan unos u šifrarnik");
        }
        if (options.isDateTime()) {
            flags.add("s vremenom");
        }
        if (notBlank(options.pattern())) {
            flags.add("uzorak " + options.pattern());
        }
        if (options.min() != null || options.max() != null) {
            flags.add("raspon " + (options.min() != null ? options.min() : "")
                    + ".." + (options.max() != null ? options.max() : ""));
        }
        if (notBlank(options.formula())) {
            flags.add("formula " + options.formula());
        }
        if (notBlank(options.buttonAction())) {
            flags.add("radnja " + options.buttonAction());
        }
        // veza mijenja i sto se smije upisati (id postojeceg zapisa) i sto se smije obrisati
        // na drugom kraju - oboje pripada u dnevnik
        if (options.targetTemplateId() != null) {
            flags.add("veza na obrazac id=" + options.targetTemplateId()
                    + (notBlank(options.displayColumnKey()) ? " (naziv: " + options.displayColumnKey() + ")" : "")
                    + (options.clearsOnTargetDelete() ? ", brisanje prekida vezu" : ", brisanje zabranjeno"));
        }
        return String.join(", ", flags);
    }

    /**
     * Ljudski opis izmjene stupca za audit: naziv (uz staro ime ako se preimenuje),
     * tip (staro -> novo ako se mijenja, inace samo trenutni), i sifrarnik
     * (staro -> novo ako se mijenja) kad je barem jedna strana vezana na sifrarnik.
     */
    private String describeColumnChange(ColumnEntry before, ColumnEntry after) {
        StringBuilder sb = new StringBuilder("Stupac ");
        if (before.key().equals(after.key())) {
            sb.append("\"").append(after.key()).append("\"");
        } else {
            sb.append("\"").append(before.key()).append("\" -> \"").append(after.key()).append("\"");
        }

        sb.append(" (tip: ");
        if (before.type().equals(after.type())) {
            sb.append(typeLabel(after.type()));
        } else {
            sb.append(typeLabel(before.type())).append(" -> ").append(typeLabel(after.type()));
        }

        // sifrarnik je bitan samo kad je stari ili novi stupac vezan na njega
        if (before.codebookId() != null || after.codebookId() != null) {
            String beforeCodebook = formatCodebook(before);
            String afterCodebook = formatCodebook(after);
            sb.append("; šifrarnik: ").append(beforeCodebook);
            if (!beforeCodebook.equals(afterCodebook)) {
                sb.append(" -> ").append(afterCodebook);
            }
        }

        // ostalo se spominje samo ako se promijenilo - inace bi svaka izmjena tipa
        // vukla za sobom pet nepromijenjenih atributa i zatrpala dnevnik
        appendChange(sb, "naziv", before.displayLabel(), after.displayLabel());
        appendChange(sb, "zadano", formatDefault(before), formatDefault(after));
        appendChange(sb, "pravila", formatFlags(before), formatFlags(after));
        return sb.append(")").toString();
    }

    private void appendChange(StringBuilder sb, String what, String before, String after) {
        if (!before.equals(after)) {
            sb.append("; ").append(what).append(": ").append(before).append(" -> ").append(after);
        }
    }

    private String formatDefault(ColumnEntry entry) {
        return hasDefault(entry) ? entry.defaultValue() : "(nema)";
    }

    private String formatFlags(ColumnEntry entry) {
        String flags = describeFlags(entry);
        return flags.isEmpty() ? "(nema)" : flags;
    }

    private String formatCodebook(ColumnEntry entry) {
        return entry.codebookId() == null ? "(nema)" : codebookLabel(entry.codebookId());
    }

    /**
     * Naziv sifrarnika za dnevnik, uz id. Sam id ne bi rekao nista onome tko dnevnik cita,
     * a sam naziv bi zavarao nakon preimenovanja - zato oboje.
     *
     * Dnevnik ne smije srusiti radnju koja se biljezi: sifrarnik koji je u meduvremenu
     * nestao ovdje zavrsi kao puki id, umjesto kao 404 nad vec spremljenom izmjenom.
     */
    private String codebookLabel(Long codebookId) {
        try {
            Codebook codebook = codebookService.requireAccessible(codebookId);
            return "\"" + codebook.getName() + "\" (id=" + codebookId + ")";
        } catch (RuntimeException e) {
            return "id=" + codebookId;
        }
    }

    // ljudski naziv tipa stupca za audit opis (isti nazivi kao u Editoru obrazaca)
    private String typeLabel(String type) {
        return switch (type) {
            case "string" -> "Tekst";
            case "number" -> "Broj";
            case "codebook" -> "Iz šifrarnika";
            case "date" -> "Datum";
            case "formula" -> "Formula";
            case "button" -> "Gumb";
            case "file" -> "Datoteka";
            case "reference" -> "Veza na obrazac";
            case "link" -> "Poveznice";
            default -> type;
        };
    }
}
