package com.example.demo.service;

import com.example.demo.dto.CodebookItemResponse;
import com.example.demo.dto.PageResponse;
import com.example.demo.dto.RecordOptionResponse;
import com.example.demo.dto.RelatedGroupResponse;
import com.example.demo.exception.RecordReferencedException;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.repository.SurveyQueryRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.repository.TemplateRepository;
import com.example.demo.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sve sto se tice VEZA medu obrascima: naziv povezanog zapisa, popis za dijalog odabira i
 * pravila brisanja zapisa na koji netko pokazuje.
 *
 * <b>Zasto zaseban razred.</b> Ovo su tri pitanja koja pripadaju vezi, a ne nijednom od dva
 * obrasca koje veza spaja. Da stoje u {@code SurveyResultService}, taj bi razred morao znati
 * za sheme SVIH obrazaca firme, a ne samo svoga - a upravo to je posao ovoga.
 *
 * <b>Baza ovdje ne pomaze.</b> Veza je broj unutar jsonb dokumenta, ne stupac, pa Postgres ne
 * zna da je to strani kljuc i nema ga tko provoditi. Cijeli referencijalni integritet je zato
 * ovdje: provjera pri upisu je u {@code SchemaValidator}, provjera pri brisanju u ovom razredu.
 */
@Service
public class ReferenceLookup {
    private static final Logger log = LoggerFactory.getLogger(ReferenceLookup.class);

    /**
     * Koliko se zapisa koji smetaju nabroji u poruci.
     *
     * Popis postoji da bi se problem dao rijesiti, a ne da bi bio potpun: tko ima 400 vezanih
     * zapisa, nece ih otvarati jedan po jedan nego ce promijeniti pristup. Ukupan broj se
     * uvijek kaze, pa se ne gubi podatak o velicini.
     */
    private static final int MAX_LISTED = 20;

    /** Koliko zapisa stane u jednu stranicu dijaloga za odabir - isto kao u tablici. */
    public static final int OPTIONS_PAGE_SIZE = SurveyResultService.PAGE_SIZE;

    private final TemplateRepository templateRepository;
    private final SurveyResultRepository surveyRepository;
    private final SurveyQueryRepository queryRepository;
    private final CodebookItemService codebookItemService;
    private final TenantContext tenantContext;

    public ReferenceLookup(TemplateRepository templateRepository,
                           SurveyResultRepository surveyRepository,
                           SurveyQueryRepository queryRepository,
                           CodebookItemService codebookItemService,
                           TenantContext tenantContext) {
        this.templateRepository = templateRepository;
        this.surveyRepository = surveyRepository;
        this.queryRepository = queryRepository;
        this.codebookItemService = codebookItemService;
        this.tenantContext = tenantContext;
    }

    /**
     * Jedan referentni stupac nekog obrasca, zajedno s obrascem u kojem stoji.
     *
     * Stupac sam ne zna gdje zivi ({@link ColumnEntry} nema ni id ni obrazac), a za brisanje
     * treba oboje: obrazac odreduje GDJE se trazi, stupac PO CEMU.
     */
    public record ReferencingColumn(Template template, ColumnEntry column) {
    }

    /**
     * Svi stupci svih obrazaca firme koji pokazuju na zadani obrazac.
     *
     * Ovo je ono sto brisanje suzava na razumnu mjeru: umjesto "pretrazi sve zapise svih
     * obrazaca", pita se samo ondje gdje veza uopce moze stajati. Broj obrazaca po firmi je
     * malen i sheme su vec u memoriji, pa je sama pretraga besplatna.
     */
    public List<ReferencingColumn> columnsTargeting(Long templateId) {
        List<ReferencingColumn> found = new ArrayList<>();
        for (Template template : templateRepository.findByCompanyId(tenantContext.require())) {
            for (ColumnEntry column : definitions(template)) {
                if ("reference".equals(column.type())
                        && templateId.equals(column.options().targetTemplateId())) {
                    found.add(new ReferencingColumn(template, column));
                }
            }
        }
        return found;
    }

    /**
     * Sve sto zadani zapis dodiruje - u OBA smjera, po obrascu i stupcu.
     *
     * Ovo je gumb "Povezani zapisi". Nista se ne konfigurira: i jedan i drugi smjer vec pisu u
     * shemama, pa bi trazenje da se to jos jednom upise rukom bilo ponavljanje koje se povrh
     * svega moze RAZICI sa stvarnoscu - stupac se preimenuje ili makne, a gumb i dalje tvrdi
     * da ga gleda.
     *
     * Smjer NAPRIJED je ovdje iz drugog razloga nego unatrag. Da veza postoji, vidi se i u
     * celiji - ali ondje stoji samo NAZIV povezanog zapisa. Ovdje se vidi njegov SADRZAJ, a to
     * je razlika izmedu "rizik pripada procesu Nabava" i "evo tog procesa, s nositeljem i
     * rokom". Zato prvo idu izlazne skupine: one su odgovor na "sto je ovo na sto pokazujem".
     *
     * Skupine bez ijednog zapisa se izostavljaju: prazan stupac i obrazac koji na ovaj zapis ne
     * pokazuje su klik koji nista ne otvara.
     *
     * Cijena je jedan {@code COUNT} po stupcu koji na obrazac pokazuje - a takvih je nekoliko,
     * i to samo na klik. Upite poslužuje GIN indeks nad {@code data} (V7).
     */
    public List<RelatedGroupResponse> groupsFor(Template template, SurveyResult record) {
        List<RelatedGroupResponse> groups = new ArrayList<>(outgoingGroups(template, record));
        groups.addAll(incomingGroups(template.getId(), record.getId()));
        return groups;
    }

    /**
     * Na koga OVAJ zapis pokazuje - po jedna skupina za svaki popunjen referentni stupac.
     *
     * Prazan stupac se preskace: veza koje nema nema sto prikazati, a prazna kartica je klik
     * koji nista ne otvara.
     */
    private List<RelatedGroupResponse> outgoingGroups(Template template, SurveyResult record) {
        Map<String, Object> data = record.getData() != null ? record.getData() : Map.of();
        List<RelatedGroupResponse> groups = new ArrayList<>();
        for (ColumnEntry column : definitions(template)) {
            Long targetId = column.options().targetTemplateId();
            if (!"reference".equals(column.type()) || targetId == null) {
                continue;
            }
            List<Long> ids = idsOf(data.get(column.key()));
            if (ids.isEmpty()) {
                continue;
            }
            // Ciljani obrazac je pri deklariranju stupca vec potvrden kao firmin, ali se ovdje
            // svejedno provjerava: shema je starija od ovog koda i mogla bi pokazivati na
            // obrazac koji je u meduvremenu nestao. Takva skupina se preskace, a ne rusi dijalog.
            Template target = templateRepository.findById(targetId)
                    .filter(found -> tenantContext.require().equals(found.getCompanyId()))
                    .orElse(null);
            if (target == null) {
                continue;
            }
            groups.add(RelatedGroupResponse.outgoing(targetId, target.getName(),
                    column.key(), column.displayLabel(), ids));
        }
        return groups;
    }

    /** Tko pokazuje na OVAJ zapis - po jedna skupina za svaki stupac koji vodi ovamo. */
    private List<RelatedGroupResponse> incomingGroups(Long templateId, Long recordId) {
        List<RelatedGroupResponse> groups = new ArrayList<>();
        for (ReferencingColumn source : columnsTargeting(templateId)) {
            long total = queryRepository.countLinkedTo(source.template().getId(),
                    source.column().key(), recordId);
            if (total == 0) {
                continue;
            }
            groups.add(RelatedGroupResponse.incoming(source.template().getId(),
                    source.template().getName(), source.column().key(),
                    source.column().displayLabel(), total));
        }
        return groups;
    }

    /** Id-evi na koje vrijednost pokazuje - jedan ili popis; sve sto nije broj se preskace. */
    private List<Long> idsOf(Object value) {
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
     * Zapisi koje treba prevezati ili obrisati prije nego se zadani zapis smije obrisati.
     *
     * Radi u dva koraka, i to je bitno: PRVO se sve prebroji, pa se tek onda odlucuje. Da se
     * veze prekidale usput, zabrana na petom stupcu ostavila bi prva cetiri vec promijenjena -
     * a brisanje koje nije proslo ne smije ostaviti nikakav trag.
     *
     * @return zapisi kojima treba prekinuti vezu (stupac trazi {@code clear}); prazno kad ih nema
     * @throws RecordReferencedException kad brisanje uopce ne prolazi
     */
    public List<Broken> requireDeletable(Long templateId, Long recordId) {
        List<String> blockers = new ArrayList<>();
        List<Broken> toClear = new ArrayList<>();
        long blockerCount = 0;

        for (ReferencingColumn source : columnsTargeting(templateId)) {
            Long sourceTemplateId = source.template().getId();
            String columnKey = source.column().key();
            long total = queryRepository.countLinkedTo(sourceTemplateId, columnKey, recordId);
            if (total == 0) {
                continue;
            }
            List<SurveyResult> linked =
                    queryRepository.findLinkedTo(sourceTemplateId, columnKey, recordId, MAX_LISTED);

            if (!source.column().options().clearsOnTargetDelete()) {
                blockerCount += total;
                blockers.add(describe(source, linked, total));
                continue;
            }
            // Zakljucan zapis blokira brisanje i kad stupac trazi prekid veze: prekid je
            // izmjena tog zapisa, a zakljucan zapis izmjene ne trpi. Da se ovdje napravi
            // iznimka, brisanje na drugom kraju bi zaobislo bravu koju je netko izricito
            // stavio - i to bez ijednog traga na samom zapisu.
            List<SurveyResult> locked = linked.stream().filter(SurveyResult::isLocked).toList();
            if (!locked.isEmpty()) {
                blockerCount += locked.size();
                blockers.add(describeLocked(source, locked));
                continue;
            }
            for (SurveyResult record : queryRepository.findLinkedTo(sourceTemplateId, columnKey,
                    recordId, Integer.MAX_VALUE)) {
                toClear.add(new Broken(record, columnKey, source.column().options().multiple()));
            }
        }

        if (!blockers.isEmpty()) {
            throw new RecordReferencedException("Zapis se ne može obrisati jer na njega pokazuje "
                    + blockerCount + " zapis(a). " + String.join(" ", blockers)
                    + " Prevežite ili obrišite te zapise, pa pokušajte ponovno.");
        }
        return toClear;
    }

    /**
     * Zapis kojem treba prekinuti vezu na zapis koji se brise.
     *
     * @param record   zapis koji drzi vezu
     * @param columnKey stupac u kojem veza stoji
     * @param multiple  drzi li stupac POPIS veza (tada se mice samo jedan clan, ne cijelo polje)
     */
    public record Broken(SurveyResult record, String columnKey, boolean multiple) {
    }

    /**
     * Podaci zapisa bez veze na zapis koji se brise.
     *
     * Kod visevrijednosnog stupca se uklanja SAMO jedan clan popisa - ostale veze tog retka s
     * ovim brisanjem nemaju veze. Kod jednovrijednosnog polje ostaje prazno, a ne nestaje:
     * razlika je nevazna za citanje, ali {@code null} jasno kaze "ovdje je nesto bilo".
     */
    public Map<String, Object> withoutLink(Broken broken, Long recordId) {
        Map<String, Object> data = broken.record().getData();
        Map<String, Object> updated = data != null ? new HashMap<>(data) : new HashMap<>();
        if (!broken.multiple()) {
            updated.remove(broken.columnKey());
            return updated;
        }
        Object value = updated.get(broken.columnKey());
        if (value instanceof List<?> list) {
            List<Object> remaining = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Number number) || number.longValue() != recordId) {
                    remaining.add(item);
                }
            }
            updated.put(broken.columnKey(), remaining);
        }
        return updated;
    }

    /**
     * Obrazac koji je nekome cilj se ne smije obrisati.
     *
     * Ovo je greska SHEME, ne podataka, pa poruka imenuje obrazac i stupac umjesto zapisa:
     * popravlja je onaj tko uredi ili makne taj stupac, a ne onaj tko prevezuje zapise.
     */
    /**
     * Veze koje bi skupno brisanje ostavilo da vise - one iz obrazaca koji OSTAJU.
     *
     * Veza izmedu dva obrasca koji oba nestaju nije prepreka: kad obojice nema, nema ni
     * stupca koji bi pokazivao u prazno. Zato se ovdje gleda samo dolazi li veza izvana.
     * Pojedinacno brisanje tu razliku ne moze napraviti - ondje je "izvana" sve osim samog
     * obrasca - pa se par koji pokazuje jedan na drugoga ne da obrisati nijednim redoslijedom.
     *
     * Vraca opis po pogodenoj vezi, spreman za najavu; prazna lista znaci da prepreke nema.
     */
    public List<String> blockersFor(List<Long> templateIds) {
        // Obrasci firme se ucitavaju JEDNOM. Prije je ovdje stajao poziv columnsTargeting po
        // svakom oznacenom obrascu, a on svaki put dohvaca sve obrasce firme - za dvadeset
        // oznacenih to je dvadeset punih dohvata za jedan te isti podatak.
        List<Template> all = templateRepository.findByCompanyId(tenantContext.require());
        Map<Long, String> namesById = all.stream()
                .collect(Collectors.toMap(Template::getId, Template::getName));

        List<String> blockers = new ArrayList<>();
        for (Template source : all) {
            if (templateIds.contains(source.getId())) {
                continue;  // veza iz obrasca koji i sam nestaje nije prepreka
            }
            for (ColumnEntry column : definitions(source)) {
                Long target = column.options().targetTemplateId();
                if (!"reference".equals(column.type()) || !templateIds.contains(target)) {
                    continue;
                }
                blockers.add("\"" + source.getName() + "\" (stupac \""
                        + column.displayLabel() + "\") pokazuje na \""
                        + namesById.getOrDefault(target, "obrazac id=" + target) + "\"");
            }
        }
        return blockers;
    }

    /** Kao {@link #blockersFor}, ali odbija radnju umjesto da samo opise prepreke. */
    public void requireDeletableTogether(List<Long> templateIds) {
        List<String> blockers = blockersFor(templateIds);
        if (!blockers.isEmpty()) {
            throw new RecordReferencedException("Obrasci se ne mogu obrisati jer na njih pokazuju "
                    + "stupci obrazaca koji ostaju: " + String.join(", ", blockers)
                    + ". Označite i te obrasce, ili im uklonite stupac s vezom.");
        }
    }

    public void requireTemplateDeletable(Long templateId) {
        List<String> sources = new ArrayList<>();
        for (ReferencingColumn source : columnsTargeting(templateId)) {
            if (!source.template().getId().equals(templateId)) {
                sources.add("\"" + source.template().getName() + "\" (stupac \""
                        + source.column().displayLabel() + "\")");
            }
        }
        if (!sources.isEmpty()) {
            throw new RecordReferencedException("Obrazac se ne može obrisati jer na njega pokazuju "
                    + "stupci drugih obrazaca: " + String.join(", ", sources)
                    + ". Uklonite ili izmijenite te stupce, pa pokušajte ponovno.");
        }
    }

    /**
     * Jedna stranica zapisa za dijalog odabira veze.
     *
     * Vraca samo id i naziv, a ne cijele zapise: dijalogu treba dvoje, a zapis nosi cijeli
     * {@code data} i podatke o zakljucanosti. Na 50 redaka je to razlika koja se vidi.
     */
    public PageResponse<RecordOptionResponse> options(Template template, String displayKey,
                                                      String search, int page) {
        int safePage = Math.max(page, 0);
        long total = queryRepository.countOptions(template.getId(), displayKey, search);
        List<SurveyResult> records =
                queryRepository.findOptions(template.getId(), displayKey, search, safePage, OPTIONS_PAGE_SIZE);

        Map<String, String> codebookNames = codebookNamesFor(template, displayKey);
        List<RecordOptionResponse> content = records.stream()
                .map(record -> new RecordOptionResponse(record.getId(),
                        label(record, displayKey, codebookNames), record.getData()))
                .toList();
        return PageResponse.of(content, safePage, OPTIONS_PAGE_SIZE, total);
    }

    /**
     * Nazivi tocno zadanih zapisa, u obliku u kojem ih ocekuje dijalog za odabir.
     *
     * Tablica zapisa ovime razrjesava veze koje na njoj stoje: prikupi id-eve sa svoje
     * stranice i jednim pozivom dobije njihove nazive. Kroz obicnu ponudu to ne bi islo -
     * ondje su zapisi poredani po nazivu i podijeljeni na stranice, pa se trazeni zapis moze
     * naci na bilo kojoj od njih.
     *
     * Id kojem vise nema zapisa se vraca kao {@code #12 (obrisan zapis)}: prazna celija bi
     * izgledala kao da veze nema, a ona postoji i pokazuje u prazno - to se mora vidjeti.
     */
    public PageResponse<RecordOptionResponse> named(Template template, String displayKey, List<Long> ids) {
        Map<Long, String> labels = labelsOf(template.getId(), displayKey, ids);
        List<RecordOptionResponse> content = new ArrayList<>();
        for (Long id : ids) {
            content.add(new RecordOptionResponse(id,
                    labels.getOrDefault(id, "#" + id + " (obrisan zapis)")));
        }
        return PageResponse.of(content, 0, content.size(), content.size());
    }

    /**
     * Nazivi zadanih zapisa jednog obrasca - id -> naziv.
     *
     * Sluzi povijesti zapisa: u tragu mora stajati "Proces A -> Proces B", a ne "12 -> 15".
     * Broj nikome nista ne znaci, a povijest se cita upravo zato da se vidi sto se dogodilo.
     *
     * Id kojem vise nema zapisa se izostavlja; pozivatelj tada zadrzi sam broj, sto je i
     * dalje istinit trag - zapis je nekad postojao pod tim brojem.
     */
    public Map<Long, String> labelsOf(Long templateId, String displayKey, List<Long> ids) {
        if (ids.isEmpty() || displayKey == null || displayKey.isBlank()) {
            return Map.of();
        }
        Template template = templateRepository.findById(templateId).orElse(null);
        if (template == null) {
            return Map.of();
        }
        Map<String, String> codebookNames = codebookNamesFor(template, displayKey);
        Map<Long, String> labels = new LinkedHashMap<>();
        for (SurveyResult record : surveyRepository.findAllById(ids)) {
            if (templateId.equals(record.getTemplateId())) {
                labels.put(record.getId(), label(record, displayKey, codebookNames));
            }
        }
        return labels;
    }

    /**
     * Naziv zapisa onako kako ga covjek vidi.
     *
     * Zapis bez vrijednosti u tom stupcu se ispisuje kao {@code #12 (bez naziva)}: prazan naziv
     * u dijalogu izgleda kao prazan redak, a "nema naziva" je i dalje zapis koji se smije
     * odabrati.
     *
     * Zasto dodatak, a ne goli {@code #12}. Celija tablice dok ceka odgovor pokazuje upravo
     * {@code #12} (naziv zivi u drugom obrascu, pa se dohvaca zasebno). Bez dodatka su ta dva
     * stanja - "jos ne znam" i "znam, i nema ga" - na ekranu ista, pa se cekalo nesto sto nikad
     * ne stigne. Isti oblik kao kod {@code #12 (obrisan zapis)}.
     */
    private String label(SurveyResult record, String displayKey, Map<String, String> codebookNames) {
        Object value = record.getData() == null ? null : record.getData().get(displayKey);
        String text = formatValue(value, codebookNames);
        return text == null || text.isBlank() ? "#" + record.getId() + " (bez naziva)" : text;
    }

    /**
     * Vrijednost u citljivom obliku.
     *
     * Sifra se prevodi u naziv: u zapisu stoji {@code "ZG"}, a covjek je odabrao "Zagreb". Da
     * se ne prevodi, dijalog za odabir bi nudio popis sifri - dakle upravo ono sto sifrarnik
     * postoji da bi se izbjeglo.
     */
    private String formatValue(Object value, Map<String, String> codebookNames) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> items) {
            List<String> parts = new ArrayList<>();
            for (Object item : items) {
                parts.add(formatValue(item, codebookNames));
            }
            return String.join(", ", parts);
        }
        String text = String.valueOf(value);
        return codebookNames.getOrDefault(text, text);
    }

    /** Sifra -> naziv, ali samo kad stupac za naziv doista dolazi iz sifrarnika. */
    private Map<String, String> codebookNamesFor(Template template, String displayKey) {
        ColumnEntry display = null;
        for (ColumnEntry column : definitions(template)) {
            if (column.key().equals(displayKey)) {
                display = column;
                break;
            }
        }
        if (display == null || display.codebookId() == null) {
            return Map.of();
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (CodebookItemResponse item : codebookItemService.getForCodebook(display.codebookId())) {
            names.put(item.code(), item.name());
        }
        return names;
    }

    /** "Obrazac "Rizici" (stupac "Proces"): #12, #15 i još 7." */
    private String describe(ReferencingColumn source, List<SurveyResult> linked, long total) {
        StringBuilder sb = new StringBuilder("Obrazac \"" + source.template().getName()
                + "\" (stupac \"" + source.column().displayLabel() + "\"): ");
        List<String> ids = new ArrayList<>();
        for (SurveyResult record : linked) {
            ids.add("#" + record.getId());
        }
        sb.append(String.join(", ", ids));
        if (total > linked.size()) {
            sb.append(" i još ").append(total - linked.size());
        }
        return sb.append(".").toString();
    }

    private String describeLocked(ReferencingColumn source, List<SurveyResult> locked) {
        List<String> ids = new ArrayList<>();
        for (SurveyResult record : locked) {
            ids.add("#" + record.getId());
        }
        return "Obrazac \"" + source.template().getName() + "\" (stupac \""
                + source.column().displayLabel() + "\"): " + String.join(", ", ids)
                + " - zaključan(i) zapis(i), veza se ne može prekinuti.";
    }

    private List<ColumnEntry> definitions(Template template) {
        return template.getDefinitions() != null ? template.getDefinitions() : List.of();
    }

    /** Za dnevnik: koliko je veza prekinuto pri brisanju. */
    void logCleared(Long recordId, int cleared) {
        if (cleared > 0) {
            log.info("Prekinuto {} vez(a) na zapis id={}", cleared, recordId);
        }
    }
}
