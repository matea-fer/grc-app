package com.example.demo.service;

import com.example.demo.auth.AuthContext;
import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.dto.CreateSurveyRequest;
import com.example.demo.dto.RecordChangeResponse;
import com.example.demo.dto.PageResponse;
import com.example.demo.dto.RelatedGroupResponse;
import com.example.demo.dto.SurveyResponse;
import com.example.demo.dto.UpdateSurveyRequest;
import com.example.demo.exception.RecordLockedException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.exception.SchemaValidationException;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.repository.SurveyQueryRepository;
import com.example.demo.repository.SurveyResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zapisi (instance) jednog templatea. Svaka operacija je vezana uz templateId iz
 * putanje, a taj template mora pripadati firmi iz konteksta - to prvo provjeri
 * {@link TemplateService#requireOwned(Long)}. Firma se nikad ne uzima iz tijela.
 *
 * Izolacija: lista vraca samo zapise tog templatea; dohvat/izmjena/brisanje po id-u
 * dodatno provjere da zapis pripada bas tom templateu. Tudi ili nepostojeci zapis
 * se tretira kao 404 (ne 403).
 *
 * Zakljucavanje ({@link #lock} / {@link #unlock}) je drugi sloj obrane, IZNAD izolacije:
 * izolacija kaze cije zapise smijes dirati, zakljucanost kaze smiju li se ti zapisi jos
 * uopce mijenjati. Zato provjera stoji odmah nakon provjere pripadnosti, a prije sheme.
 */
@Service
public class SurveyResultService {
    private static final Logger log = LoggerFactory.getLogger(SurveyResultService.class);

    /**
     * Koliko redaka stane na jednu stranicu.
     *
     * Stalna vrijednost, a ne parametar zahtjeva: velicina stranice nije izbor korisnika
     * (sucelje ga ne nudi), a parametar koji nitko ne mijenja je samo jos jedan nacin da
     * netko zatrazi 100.000 redaka odjednom i time ponisti smisao stranicenja.
     */
    public static final int PAGE_SIZE = 50;

    private final SurveyResultRepository repository;
    private final SurveyQueryRepository queryRepository;
    private final ColumnDefinitionService columnDefinitionService;
    private final SchemaValidator validator;
    private final TemplateService templateService;
    private final ColumnSequenceService columnSequenceService;
    private final CodebookItemService codebookItemService;
    private final FormulaEvaluator formulaEvaluator;
    private final AttachmentCleanup attachmentCleanup;
    private final RecordChangeService recordChangeService;
    private final LogService logService;
    private final AuthContext authContext;
    private final ReferenceLookup referenceLookup;

    public SurveyResultService(SurveyResultRepository repository,
                               SurveyQueryRepository queryRepository,
                               ColumnDefinitionService columnDefinitionService,
                               SchemaValidator validator,
                               TemplateService templateService,
                               ColumnSequenceService columnSequenceService,
                               CodebookItemService codebookItemService,
                               FormulaEvaluator formulaEvaluator,
                               AttachmentCleanup attachmentCleanup,
                               RecordChangeService recordChangeService,
                               LogService logService,
                               AuthContext authContext,
                               ReferenceLookup referenceLookup) {
        this.repository = repository;
        this.queryRepository = queryRepository;
        this.columnDefinitionService = columnDefinitionService;
        this.validator = validator;
        this.templateService = templateService;
        this.columnSequenceService = columnSequenceService;
        this.codebookItemService = codebookItemService;
        this.formulaEvaluator = formulaEvaluator;
        this.attachmentCleanup = attachmentCleanup;
        this.recordChangeService = recordChangeService;
        this.logService = logService;
        this.authContext = authContext;
        this.referenceLookup = referenceLookup;
    }

    /**
     * Jedna stranica zapisa obrasca, uz pretragu i sortiranje - sve troje radi BAZA.
     *
     * Dok je ekran dobivao sve zapise i filtrirao ih u pregledniku, otvaranje obrasca s
     * 20.000 redaka znacilo je desetke megabajta prijenosa i stotine tisuca celija u
     * stranici. Ovako s posluziteljem putuje uvijek najvise {@link #PAGE_SIZE} redaka,
     * bez obzira koliko ih obrazac ima.
     *
     * @param sortBy  kljuc stupca iz sheme, ili {@code null} za poredak unosa
     * @param sortDir "desc" za silazno; sve ostalo je uzlazno
     * @param filters uvjeti oblika {@code kljuc:vrijednost}, spojeni s I (svaki dodatno suzava)
     */
    public PageResponse<SurveyResponse> getAll(Long templateId, int page, String sortBy,
                                               String sortDir, List<String> filters) {
        return getAll(templateId, page, sortBy, sortDir, filters, null);
    }

    /**
     * Ista stranica, ali suzena na zadane id-eve.
     *
     * Sluzi dijalogu povezanih zapisa u smjeru naprijed ("evo zapisa na koje ovaj pokazuje").
     * Suzenje je uz filtre, a ne umjesto njih: to su dva razlicita pitanja i oba se smiju
     * postaviti odjednom.
     *
     * @param ids id-evi na koje se dohvat suzava; prazno znaci "bez suzenja"
     */
    public PageResponse<SurveyResponse> getAll(Long templateId, int page, String sortBy,
                                               String sortDir, List<String> filters, List<Long> ids) {
        templateService.requireOwned(templateId);
        List<ColumnDefinitionResponse> schema = columnDefinitionService.getForTemplate(templateId);
        int safePage = Math.max(page, 0);

        List<SurveyFilter> conditions = new ArrayList<>();
        for (String raw : filters == null ? List.<String>of() : filters) {
            SurveyFilter filter = parseFilter(raw, schema);
            // Filtar bez ijednog mogucega pogotka (npr. naziv iz sifrarnika koji ne postoji)
            // ne treba pitati bazu - odgovor je vec poznat, a prazan popis sifara bi u upitu
            // dao uvjet bez ijednog clana.
            if (filter == null) {
                return PageResponse.empty(safePage, PAGE_SIZE);
            }
            conditions.add(filter);
        }

        ColumnDefinitionResponse sortColumn = sortColumn(sortBy, schema);
        boolean descending = "desc".equalsIgnoreCase(sortDir);
        long total = queryRepository.count(templateId, conditions, ids);
        List<SurveyResponse> content = queryRepository
                .find(templateId, conditions, ids,
                        sortColumn == null ? null : sortColumn.columnKey(),
                        sortColumn != null && isNumeric(sortColumn),
                        descending, safePage, PAGE_SIZE)
                .stream().map(SurveyResponse::from).toList();
        return PageResponse.of(content, safePage, PAGE_SIZE, total);
    }

    public SurveyResponse getOne(Long templateId, Long id) {
        return SurveyResponse.from(findOwnedOrThrow(templateId, id));
    }

    // Transakcija obuhvaca i provjeru jedinstvenosti i sam upis. Zastita nije potpuna -
    // dva istovremena unosa mogu procitati isto stanje i oba proci - jer bi pravu garanciju
    // dalo tek jedinstveno ogranicenje u bazi, a kljucevi ovdje nisu stupci nego sadrzaj jsonb-a.
    @Transactional
    public SurveyResponse create(Long templateId, CreateSurveyRequest request) {
        Template template = templateService.requireOwned(templateId);
        List<ColumnDefinitionResponse> schema = columnDefinitionService.getForTemplate(templateId);
        Map<String, Object> data = prepare(templateId, request.data(), schema, null);
        validator.validate(data, schema, uniqueLookup(templateId, null));

        SurveyResult surveyResult = new SurveyResult();
        surveyResult.setCompanyId(template.getCompanyId());
        surveyResult.setTemplateId(templateId);
        surveyResult.setData(data);
        SurveyResult saved = repository.save(surveyResult);

        // Stvaranje se biljezi kao promjena "iz nicega": bez toga bi povijest pocinjala od
        // prve IZMJENE, pa se ne bi vidjelo s cime je zapis uopce nastao - a to je prvo sto
        // se pita kad se trazi kako je vrijednost postala ovakva.
        captureWithLabels(saved.getId(), templateId, template.getCompanyId(), null, data, schema);

        log.info("Created SurveyResult id={} templateId={}", saved.getId(), templateId);
        logService.record("RECORD_CREATED", "Zapis id=" + saved.getId() + " u obrascu id=" + templateId);
        return SurveyResponse.from(saved);
    }

    // puna zamjena podataka - namjerno, ne merge. Frontend racuna cijeli data
    // objekt prije slanja i ocekuje da se tocno to spremi (uz iznimku readOnly stupaca,
    // koje server nametne bez obzira na poslano).
    @Transactional
    public SurveyResponse update(Long templateId, Long id, UpdateSurveyRequest request) {
        // pripadnost i postojanje se provjere prvo: tudi/nepostojeci zapis vrati 404, a ne 400
        SurveyResult surveyResult = findOwnedOrThrow(templateId, id);
        // zakljucanost se provjeri prije svega ostalog: nema smisla racunati formule ni
        // provjeravati shemu za izmjenu koja ionako ne prolazi
        requireUnlocked(surveyResult);
        List<ColumnDefinitionResponse> schema = columnDefinitionService.getForTemplate(templateId);
        // Preslika starog stanja se uzima PRIJE pripreme: nakon setData je nema odakle
        // dohvatiti, a bez nje se ne zna iz cega je vrijednost promijenjena.
        Map<String, Object> before = surveyResult.getData() == null
                ? Map.of() : new HashMap<>(surveyResult.getData());
        Map<String, Object> data = prepare(templateId, request.data(), schema, surveyResult.getData());
        validator.validate(data, schema, uniqueLookup(templateId, id));
        rejectSelfLink(id, data, schema);

        surveyResult.setData(data);
        SurveyResponse response = SurveyResponse.from(repository.save(surveyResult));
        captureWithLabels(id, templateId, surveyResult.getCompanyId(), before, data, schema);
        logService.record("RECORD_UPDATED", "Zapis id=" + id + " u obrascu id=" + templateId);
        return response;
    }

    /**
     * Povijest promjena jednog zapisa - sadrzaj dijaloga "Povijest".
     *
     * Ide kroz istu provjeru pripadnosti kao i citanje samog zapisa: tudi ili nepostojeci
     * zapis vraca 404. Bez toga bi povijest bila rupa u izolaciji - trag promjena otkriva
     * sadrzaj tudih redaka jednako dobro kao i sami redci.
     */
    public List<RecordChangeResponse> history(Long templateId, Long id) {
        SurveyResult surveyResult = findOwnedOrThrow(templateId, id);
        return recordChangeService.history(surveyResult.getId(),
                columnDefinitionService.getForTemplate(templateId));
    }

    /**
     * Tko sve pokazuje na ovaj zapis - sadrzaj gumba "Povezani zapisi".
     *
     * Ide kroz istu provjeru pripadnosti kao i citanje samog zapisa: tudi ili nepostojeci
     * zapis vraca 404. Bez toga bi ovo bila rupa u izolaciji - popis "tko pokazuje na zapis 12"
     * odaje i da zapis 12 postoji i koliko toga za njega visi.
     */
    public List<RelatedGroupResponse> related(Long templateId, Long id) {
        SurveyResult surveyResult = findOwnedOrThrow(templateId, id);
        return referenceLookup.groupsFor(templateService.requireOwned(templateId), surveyResult);
    }

    /**
     * Zakljucaj zapis - nakon toga se ne smije mijenjati, brisati ni mijenjati mu prilozi.
     *
     * Zakljucava SVATKO tko smije unositi zapise: klik na gumb znaci "gotov sam, saljem na
     * validaciju", a to radi onaj tko je redak i popunio. Otkljucavanje je drugi par rukava
     * ({@link #unlock}).
     *
     * Vec zakljucan zapis vraca se nedirnut, bez greske i bez novog zapisa u dnevniku:
     * radnja je izjava o stanju ("neka bude zakljucan"), a ne dogadaj koji se broji. Dva
     * otvorena prozora tako ne proizvode ni gresku ni dvostruki trag.
     */
    @Transactional
    public SurveyResponse lock(Long templateId, Long id) {
        SurveyResult surveyResult = findOwnedOrThrow(templateId, id);
        if (surveyResult.isLocked()) {
            return SurveyResponse.from(surveyResult);
        }

        surveyResult.setLockedAt(Instant.now());
        surveyResult.setLockedBy(authContext.require().username());
        SurveyResponse response = SurveyResponse.from(repository.save(surveyResult));

        log.info("Zakljucan SurveyResult id={} templateId={}", id, templateId);
        logService.record("RECORD_LOCKED", "Zapis id=" + id + " u obrascu id=" + templateId);
        return response;
    }

    /**
     * Otkljucaj zapis - samo administrator (globalni ili firmin).
     *
     * Ovdje je i cijela tezina znacajke: zakljucavanje bez ogranicenog otkljucavanja ne bi
     * bilo zastita nego neugodnost, jer bi ga onaj tko je zakljucao odmah mogao poništiti.
     * Doseg je i dalje firma - tudi zapis je 404 jos u {@link #findOwnedOrThrow}, pa admin
     * jedne firme ne moze otkljucati tudi zapis ni kad zna njegov id.
     *
     * Otkljucan zapis se vraca nedirnut, iz istog razloga kao u {@link #lock}.
     */
    @Transactional
    public SurveyResponse unlock(Long templateId, Long id) {
        SurveyResult surveyResult = findOwnedOrThrow(templateId, id);
        authContext.requireAnyAdmin();
        if (!surveyResult.isLocked()) {
            return SurveyResponse.from(surveyResult);
        }

        String lockedBy = surveyResult.getLockedBy();
        surveyResult.setLockedAt(null);
        surveyResult.setLockedBy(null);
        SurveyResponse response = SurveyResponse.from(repository.save(surveyResult));

        log.info("Otkljucan SurveyResult id={} templateId={}", id, templateId);
        logService.record("RECORD_UNLOCKED", "Zapis id=" + id + " u obrascu id=" + templateId
                + (lockedBy != null ? " (zaključao " + lockedBy + ")" : ""));
        return response;
    }

    @Transactional
    public void delete(Long templateId, Long id) {
        SurveyResult surveyResult = findOwnedOrThrow(templateId, id);
        // brisanje je izmjena kao i svaka druga - zapravo najveca; zakljucan zapis je ne trpi
        requireUnlocked(surveyResult);
        // Veze iz drugih obrazaca: ili zabranjuju brisanje (zadano), ili se prekidaju - ovisno
        // o tome sto je odabrao onaj tko je definirao stupac. Provjera ide PRIJE svega ostalog:
        // brisanje koje ne prolazi ne smije usput odnijeti priloge ni povijest.
        int unlinked = clearLinks(referenceLookup.requireDeletable(templateId, id), id);
        // prilozi zive u vlastitoj tablici, pa ih brisanje zapisa samo po sebi ne odnosi
        int attachments = attachmentCleanup.forSurvey(id);
        // isto vrijedi i za povijest: bez ovoga bi ostao trag promjena zapisa kojeg nema,
        // a do njega vise ne bi vodio nijedan ekran
        recordChangeService.forSurvey(id);
        repository.deleteById(surveyResult.getId());
        log.info("Deleted SurveyResult id={} templateId={} with {} attachment(s), {} link(s) cleared",
                id, templateId, attachments, unlinked);
        logService.record("RECORD_DELETED", "Zapis id=" + id + " iz obrasca id=" + templateId
                + (attachments > 0 ? " i " + attachments + " prilog(a)" : "")
                + (unlinked > 0 ? " (prekinuto " + unlinked + " vez(a))" : ""));
    }

    /**
     * Prekini veze zapisa koji se brise - kod stupaca koji to izricito traze.
     *
     * Svaki prekid ulazi u povijest TOG zapisa, a ne samo u dnevnik. Bez toga bi vlasnik
     * rizika vidio da mu je polje "Proces" jednog dana prazno, a nigdje ne bi pisalo zasto -
     * brisanje na drugom kraju bilo bi jedini nacin da podatak nestane bez traga.
     *
     * @return broj prekinutih veza
     */
    private int clearLinks(List<ReferenceLookup.Broken> broken, Long deletedId) {
        // sheme se pamte po obrascu: prekinute veze cesto dolaze iz istog obrasca, pa bi
        // dohvat po zapisu bio isti upit ponovljen stotinu puta
        Map<Long, List<ColumnDefinitionResponse>> schemas = new HashMap<>();
        for (ReferenceLookup.Broken link : broken) {
            SurveyResult record = link.record();
            List<ColumnDefinitionResponse> schema = schemas.computeIfAbsent(record.getTemplateId(),
                    columnDefinitionService::getForTemplate);
            Map<String, Object> before = record.getData() == null
                    ? Map.of() : new HashMap<>(record.getData());
            Map<String, Object> after = referenceLookup.withoutLink(link, deletedId);
            record.setData(after);
            repository.save(record);
            captureWithLabels(record.getId(), record.getTemplateId(), record.getCompanyId(),
                    before, after, schema);
        }
        referenceLookup.logCleared(deletedId, broken.size());
        return broken.size();
    }

    /**
     * Zabiljezi promjenu retka, ali s NAZIVIMA povezanih zapisa umjesto njihovih id-eva.
     *
     * "12 -> 15" nikome nista ne znaci; povijest se cita da bi se vidjelo sto se dogodilo, pa
     * ondje mora stajati "Nabava -> Prodaja". Naziv se pritom uzima u trenutku promjene i
     * ostaje zapisan kao tekst - to je i ispravno: trag opisuje kako se zapis zvao TADA, a ne
     * kako se zove danas.
     */
    private void captureWithLabels(Long surveyId, Long templateId, Long companyId,
                                   Map<String, Object> before, Map<String, Object> after,
                                   List<ColumnDefinitionResponse> schema) {
        recordChangeService.capture(surveyId, templateId, companyId,
                withReferenceLabels(before, schema), withReferenceLabels(after, schema));
    }

    /** Preslika podataka u kojoj su vrijednosti referentnih stupaca zamijenjene nazivima. */
    private Map<String, Object> withReferenceLabels(Map<String, Object> data,
                                                    List<ColumnDefinitionResponse> schema) {
        if (data == null || data.isEmpty()) {
            return data;
        }
        Map<String, Object> result = null;
        for (ColumnDefinitionResponse column : schema) {
            if (!column.isReference() || column.options().targetTemplateId() == null) {
                continue;
            }
            Object value = data.get(column.columnKey());
            List<Long> ids = referencedIds(value);
            if (ids.isEmpty()) {
                continue;
            }
            Map<Long, String> labels = referenceLookup.labelsOf(column.options().targetTemplateId(),
                    column.options().displayColumnKey(), ids);
            result = result != null ? result : new HashMap<>(data);
            result.put(column.columnKey(), value instanceof List<?>
                    ? ids.stream().map(id -> labels.getOrDefault(id, "#" + id)).toList()
                    : labels.getOrDefault(ids.get(0), "#" + ids.get(0)));
        }
        return result != null ? result : data;
    }

    /** Id-evi na koje vrijednost pokazuje - jedan ili popis; sve sto nije broj se preskace. */
    private List<Long> referencedIds(Object value) {
        if (value instanceof Number number) {
            return List.of(number.longValue());
        }
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Number number) {
                ids.add(number.longValue());
            }
        }
        return ids;
    }

    /**
     * Zapis ne smije pokazivati sam na sebe.
     *
     * Obrazac koji pokazuje na samog sebe je dopusten i trazen - hijerarhija (proces ->
     * nadredeni proces) upravo tako i izgleda. Ali zapis koji je sam sebi nadreden je uvijek
     * pogreska pri unosu: ne opisuje nista, a svaki prikaz stabla se na njemu vrti u krug.
     *
     * Dulji krugovi (A -> B -> A) se NE traze: provjera bi za svaki upis morala prosetati
     * lanac neogranicene dubine, a takav se raspored ne pravi slucajno.
     */
    private void rejectSelfLink(Long id, Map<String, Object> data, List<ColumnDefinitionResponse> schema) {
        for (ColumnDefinitionResponse column : schema) {
            if (!column.isReference()) {
                continue;
            }
            if (referencedIds(data.get(column.columnKey())).contains(id)) {
                throw new SchemaValidationException(List.of("column \"" + column.columnKey()
                        + "\": a record cannot link to itself"));
            }
        }
    }

    /**
     * Jedan uvjet pretrage, preveden iz {@code kljuc:vrijednost} u nesto sto baza moze pitati.
     *
     * Dijeli se na PRVOJ dvotocki: kljuc stupca je nas (Editor ga ne pusta s dvotockom), a
     * vrijednost je korisnikov tekst i u njoj dvotocka smije stajati.
     *
     * @return {@code null} kad je vec sad jasno da uvjet nitko ne moze zadovoljiti
     * @throws SchemaValidationException ako stupac nije iz sheme ovog obrasca
     */
    private SurveyFilter parseFilter(String raw, List<ColumnDefinitionResponse> schema) {
        int colon = raw == null ? -1 : raw.indexOf(':');
        if (colon <= 0) {
            throw new SchemaValidationException(List.of("filter must be written as \"column:value\""));
        }
        String columnKey = raw.substring(0, colon);
        String value = raw.substring(colon + 1).trim();
        ColumnDefinitionResponse column = requireColumn(columnKey, schema);
        if (value.isEmpty()) {
            return null;
        }

        if ("codebook".equals(column.columnType())) {
            // korisnik vidi NAZIV ("Zagreb"), a u zapisu stoji SIFRA ("ZG") - prijevod ide
            // ovdje, jer je ovo jedino mjesto koje zna i stupac i njegov sifrarnik
            List<String> codes = codebookItemService.codesMatching(column.codebookId(), value);
            return codes.isEmpty() ? null : SurveyFilter.codes(columnKey, codes);
        }
        if (column.isReference()) {
            // Veza se ne trazi po nazivu nego po ID-u odabranog zapisa: korisnik ga bira iz
            // dijaloga, pa nema sto pogadati. Ovim istim putem radi i dijalog "Povezani zapisi",
            // koji pita "tko pokazuje na zapis 12".
            try {
                return SurveyFilter.reference(columnKey, Long.valueOf(value));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (isNumeric(column)) {
            try {
                return SurveyFilter.number(columnKey, new BigDecimal(value.replace(',', '.')));
            } catch (NumberFormatException e) {
                // "abc" u brojcanom stupcu nije greska nego upit bez pogotka
                return null;
            }
        }
        return SurveyFilter.text(columnKey, escapeForLike(value));
    }

    /**
     * Stupac po kojem se sortira, ili {@code null} kad se ne sortira.
     *
     * Provjera protiv sheme nije formalnost: naziv stupca dolazi iz zahtjeva i zavrsava u
     * {@code ORDER BY}. Bez nje bi klijent mogao sortirati po kljucu kojeg nema (tiho prazan
     * poredak), a kod stupaca koji nemaju vrijednost bi obecala poredak koji ne postoji.
     */
    private ColumnDefinitionResponse sortColumn(String sortBy, List<ColumnDefinitionResponse> schema) {
        if (sortBy == null || sortBy.isBlank()) {
            return null;
        }
        ColumnDefinitionResponse column = requireColumn(sortBy, schema);
        if (!isSortable(column)) {
            throw new SchemaValidationException(List.of(
                    "column \"" + sortBy + "\" cannot be sorted: type \"" + column.columnType() + "\""));
        }
        return column;
    }

    private ColumnDefinitionResponse requireColumn(String columnKey, List<ColumnDefinitionResponse> schema) {
        for (ColumnDefinitionResponse column : schema) {
            if (column.columnKey().equals(columnKey)) {
                return column;
            }
        }
        throw new SchemaValidationException(List.of("unknown column \"" + columnKey + "\""));
    }

    /**
     * Stupci koji nemaju po cemu biti poredani: gumb i datoteka u zapisu ne drze nista
     * (prilozi su u vlastitoj tablici), poveznice drze POPIS, a i visevrijednosni sifrarnik -
     * poredak popisa nije poredak nicega sto korisnik vidi.
     */
    private boolean isSortable(ColumnDefinitionResponse column) {
        if ("button".equals(column.columnType()) || "file".equals(column.columnType())
                || "link".equals(column.columnType())) {
            return false;
        }
        // Veza drzi ID, a korisnik vidi NAZIV: poredak po id-u bi bio poredak po necemu sto
        // se na ekranu ne vidi, a poredak po nazivu bi trazio spajanje s drugim obrascem
        // unutar upita koji vec radi nad jsonb-om.
        if (column.isReference()) {
            return false;
        }
        return !column.isMultiValued();
    }

    /** Brojcani su i broj i formula - formula sprema izracunatu vrijednost, dakle broj. */
    private boolean isNumeric(ColumnDefinitionResponse column) {
        return "number".equals(column.columnType()) || "formula".equals(column.columnType());
    }

    /**
     * Znakovi koje LIKE cita kao uzorak, a korisnik ih je upisao kao obican tekst.
     *
     * Bez ovoga bi "100%" naslo sve sto pocinje sa "100", a "_" bilo koji jedan znak - dakle
     * pretraga bi tiho radila nesto drugo nego sto pise. Obrnuta kosa crta je znak bijega,
     * pa se mora pobjeci prva.
     */
    private String escapeForLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Pitanje jedinstvenosti prevedeno u upit nad bazom, po istom pravilu jednakosti koje
     * opisuje {@code SchemaValidator.sameValue}: tekst bez razlike velikih slova i rubnih
     * razmaka, brojevi po vrijednosti.
     *
     * @param excludeId redak koji se upravo sprema; {@code null} kod novog zapisa
     */
    private UniqueValueLookup uniqueLookup(Long templateId, Long excludeId) {
        return (columnKey, value) -> {
            if (value instanceof Number number) {
                return queryRepository.existsWithNumber(templateId, excludeId, columnKey,
                        new BigDecimal(number.toString()));
            }
            // Sve ostalo se usporeduje kao tekst. U praksi ovuda ne prolazi nista: jedinstven
            // smije biti samo stupac koji drzi jednu vrijednost (popis je izricito zabranjen u
            // ColumnDefinitionService), a te su vrijednosti tekst ili broj.
            return queryRepository.existsWithText(templateId, excludeId, columnKey, String.valueOf(value));
        };
    }

    /**
     * Zakljucan zapis ne trpi nikakvu izmjenu -> 409.
     *
     * Staticka je namjerno: isto pravilo vrijedi i za priloge ({@link AttachmentService}),
     * a prilog nije zapis, pa bi injektiranje cijelog ovog servisa ondje povuklo i pola
     * njegovih ovisnosti zbog jednog {@code if}-a. Poruka pritom ostaje na jednom mjestu -
     * korisnik na oba puta dobiva isto objasnjenje, ukljucujuci i tko je zakljucao.
     */
    static void requireUnlocked(SurveyResult surveyResult) {
        if (surveyResult.isLocked()) {
            throw new RecordLockedException("Zapis je zaključan"
                    + (surveyResult.getLockedBy() != null ? " (" + surveyResult.getLockedBy() + ")" : "")
                    + " i ne može se mijenjati. Otključati ga može administrator.");
        }
    }

    /**
     * Zapis trazenog id-a, ali samo ako pripada bas ovom templateu (koji je vec
     * potvrden kao vlasnistvo firme). Tudi ili nepostojeci zapis jednako zavrsavaju
     * kao 404 - poziv ne smije razlikovati "ne postoji" od "nije tvoj".
     */
    private SurveyResult findOwnedOrThrow(Long templateId, Long id) {
        templateService.requireOwned(templateId);
        return repository.findById(id)
                .filter(survey -> templateId.equals(survey.getTemplateId()))
                .orElseThrow(() -> new ResourceNotFoundException("SurveyResult", id));
    }

    /**
     * Podaci retka dovedeni u oblik u kojem se stvarno spremaju, prije provjere sheme.
     *
     * Redoslijed nije proizvoljan:
     *   1. {@code enforceReadOnly} - odbaci sve sto klijent nije smio poslati; tek nakon
     *      toga se zna sto je stvarno prazno;
     *   2. slobodan unos -> sifra; mora prije provjere, jer bi validator upisani NAZIV
     *      inace odbio kao nepostojecu sifru;
     *   3. automatski redni broj; tek nakon 1., inace bi vrijednost koju je klijent
     *      podmetnuo sprijecila dodjelu;
     *   4. formule; zadnje, jer racunaju iz svega prethodnog.
     *
     * @param stored spremljeni podaci retka, ili {@code null} kad je rijec o novom zapisu
     */
    private Map<String, Object> prepare(Long templateId,
                                        Map<String, Object> incoming,
                                        List<ColumnDefinitionResponse> schema,
                                        Map<String, Object> stored) {
        Map<String, Object> data = enforceReadOnly(incoming, schema, stored);
        data = resolveFreeEntries(data, schema);
        data = assignSequenceNumbers(templateId, data, schema);
        return formulaEvaluator.computeAll(schema, data);
    }

    /**
     * Kod stupca sa slobodnim unosom klijent salje ono sto je covjek upisao - naziv, a ne
     * sifru; sifru pri unosu zapisa nitko ni ne vidi. Ovdje se pretvara u sifru, a vrijednost
     * koje u sifrarniku jos nema se u njega dodaje.
     *
     * Upisana postojeca sifra prolazi nedirnuta: {@code ensureCode} usporeduje i po sifri i
     * po nazivu, pa ponovno spremanje istog retka ne stvara duplikat.
     *
     * Visevrijednosni stupac nosi POPIS, u kojem se postojece sifre i novoupisani nazivi
     * mijesaju - korisnik je dio vrijednosti odabrao, a dio upisao. Zato se popis rjesava clan
     * po clan istim pravilom; razlika je samo u omotu.
     */
    private Map<String, Object> resolveFreeEntries(Map<String, Object> data,
                                                   List<ColumnDefinitionResponse> schema) {
        Map<String, Object> result = null;
        for (ColumnDefinitionResponse column : schema) {
            if (!column.options().allowNewValues() || column.codebookId() == null) {
                continue;
            }
            Object value = data.get(column.columnKey());
            Object resolved = value instanceof List<?> list
                    ? resolveFreeList(column.codebookId(), list)
                    : resolveFreeText(column.codebookId(), value);
            if (resolved != null && !resolved.equals(value)) {
                result = result != null ? result : new HashMap<>(data);
                result.put(column.columnKey(), resolved);
            }
        }
        return result != null ? result : data;
    }

    /** @return sifra, ili null kad vrijednost nije tekst koji bi je mogao dati */
    private String resolveFreeText(Long codebookId, Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return codebookItemService.ensureCode(codebookId, text);
    }

    /**
     * Popis sifara, s novoupisanim nazivima pretvorenima u sifre.
     *
     * Ista vrijednost upisana dvaput (npr. odabrana pa jos jednom utipkana) daje istu sifru,
     * pa se duplikati ovdje izbacuju - inace bi zapis nosio istu stavku dva puta.
     */
    private List<Object> resolveFreeList(Long codebookId, List<?> values) {
        List<Object> resolved = new ArrayList<>();
        for (Object value : values) {
            String code = resolveFreeText(codebookId, value);
            Object item = code != null ? code : value;
            if (!resolved.contains(item)) {
                resolved.add(item);
            }
        }
        return resolved;
    }

    /**
     * Stupcu s automatskim rednim brojem dodijeli sljedeci broj - ali samo ako ga jos nema.
     *
     * Uvjet "samo ako ga nema" je ono sto cuva postojece zapise: izmjena vec spremljenog
     * retka mu ne smije promijeniti redni broj, a {@code enforceReadOnly} je njegovu staru
     * vrijednost vec vratio na mjesto. Novi broj tako dobiva samo redak koji ga stvarno nema -
     * nov zapis, ili stariji iz vremena prije nego je stupac postao automatski.
     */
    private Map<String, Object> assignSequenceNumbers(Long templateId,
                                                      Map<String, Object> data,
                                                      List<ColumnDefinitionResponse> schema) {
        Map<String, Object> result = null;
        for (ColumnDefinitionResponse column : schema) {
            if (!column.options().autoIncrement() || data.get(column.columnKey()) != null) {
                continue;
            }
            result = result != null ? result : new HashMap<>(data);
            result.put(column.columnKey(), columnSequenceService.next(templateId, column.columnKey()));
        }
        return result != null ? result : data;
    }

    /**
     * Vrijednosti readOnly stupaca postavlja server, ne klijent: sto god je stiglo u tijelu
     * za takav stupac se odbacuje. Kod novog zapisa vrijednost dolazi iz zadane vrijednosti
     * stupca, kod izmjene ostaje ona koja je vec spremljena (a ako je redak nema - npr. stupac
     * je dodan naknadno - popuni se zadanom vrijednoscu).
     *
     * Namjerno tiho, bez greske: forma takva polja uopce ne nudi na unos, pa je razlika u
     * poslanom sadrzaju stvar klijenta, a ne nesto sto korisnik treba ispravljati.
     *
     * @param stored spremljeni podaci retka, ili {@code null} kad je rijec o novom zapisu
     */
    private Map<String, Object> enforceReadOnly(Map<String, Object> incoming,
                                                List<ColumnDefinitionResponse> schema,
                                                Map<String, Object> stored) {
        Map<String, Object> data = incoming != null ? new HashMap<>(incoming) : new HashMap<>();
        for (ColumnDefinitionResponse column : schema) {
            if (!column.readOnly()) {
                continue;
            }
            String key = column.columnKey();
            Object value = stored != null ? stored.get(key) : null;
            if (value == null) {
                value = validator.parseDefaultValue(column);
            }
            if (value == null) {
                data.remove(key);
            } else {
                data.put(key, value);
            }
        }
        return data;
    }
}
