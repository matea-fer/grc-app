package com.example.demo.service;

import com.example.demo.auth.AuthContext;
import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.dto.RecordChangeResponse;
import com.example.demo.model.RecordChange;
import com.example.demo.repository.RecordChangeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Revizijski trag zapisa: koje se polje promijenilo, iz cega, u sto, tko i kada.
 *
 * <b>Zasto ovo nije dio {@link LogService}.</b> Dnevnik odgovara na "tko je i kada radio" i
 * biljezi jednu akciju kao jedan redak; ovo odgovara na "kako je ova vrijednost postala
 * ovakva" i biljezi po jedan redak za svako dirnuto polje. Razlicita pitanja, razliciti upiti
 * (dnevnik po firmi i vremenu, ovo po jednom zapisu), razlicite kolicine.
 *
 * <b>I zato se ponasaju suprotno pri gresci.</b> {@code LogService} svoju gresku proguta i
 * pusti akciju da prode - audit ne smije oboriti posao koji je uspio. Ovdje je obrnuto: upis
 * ide u ISTU transakciju kao i sama izmjena, bez {@code REQUIRES_NEW} i bez try/catch, pa pad
 * povlaci i izmjenu. Razlog je sto ovo nije biljeska O poslu nego DIO zapisa: promjena koja se
 * dogodila a nije zabiljezena je tocno ono stanje koje alat za usklađenost ne smije dopustiti.
 * Bolje da izmjena ne prode, nego da prode bez traga.
 */
@Service
public class RecordChangeService {
    private static final Logger log = LoggerFactory.getLogger(RecordChangeService.class);

    /** Duljina stupca u bazi; duze vrijednosti se krate da upis ne padne na sadrzaju. */
    private static final int MAX_VALUE = 1000;
    private static final String TRUNCATED = "...";

    private final RecordChangeRepository repository;
    private final AuthContext authContext;

    public RecordChangeService(RecordChangeRepository repository, AuthContext authContext) {
        this.repository = repository;
        this.authContext = authContext;
    }

    /**
     * Zabiljezi razliku izmedu starog i novog stanja retka.
     *
     * @param before spremljeni podaci prije izmjene; {@code null} kod novog zapisa, pa se sve
     *               popunjene vrijednosti zabiljeze kao "iz nicega"
     * @param after  podaci kakvi se upravo spremaju
     * @return broj zabiljezenih promjena (0 kad se nista nije stvarno promijenilo)
     */
    @Transactional
    public int capture(Long surveyId, Long templateId, Long companyId,
                       Map<String, Object> before, Map<String, Object> after) {
        List<RecordChange> changes = diff(surveyId, templateId, companyId, before, after);
        if (changes.isEmpty()) {
            return 0;
        }
        repository.saveAll(changes);
        log.info("Zabiljezeno {} promjen(a) na zapisu id={}", changes.size(), surveyId);
        return changes.size();
    }

    /**
     * Prilozena datoteka - u tragu stoji kao da je polje dobilo vrijednost.
     *
     * Prilog NIJE vrijednost zapisa (zivi u vlastitoj tablici i stupca tipa "file" u
     * {@code data} nema), pa ga {@link #capture} nikad ne vidi. Zato ide zasebnim putem - ali
     * u ISTU tablicu i pod ISTI kljuc stupca: onome tko otvori povijest je prilaganje dokaza
     * promjena retka jednako kao i upisana vrijednost, a cesto i jedina koja se broji.
     *
     * Sudara se ne treba bojati: stupac tipa "file" u {@code data} ne drzi nista, pa pod tim
     * kljucem u tragu ne moze stajati nista drugo.
     */
    @Transactional
    public void attachmentAdded(Long surveyId, Long templateId, Long companyId,
                                String columnKey, String fileName) {
        captureAttachment(surveyId, templateId, companyId, columnKey, null, fileName);
    }

    /** Uklonjena datoteka - polje je vrijednost izgubilo. */
    @Transactional
    public void attachmentRemoved(Long surveyId, Long templateId, Long companyId,
                                  String columnKey, String fileName) {
        captureAttachment(surveyId, templateId, companyId, columnKey, fileName, null);
    }

    private void captureAttachment(Long surveyId, Long templateId, Long companyId,
                                   String columnKey, String oldValue, String newValue) {
        repository.save(new RecordChange(surveyId, templateId, companyId, columnKey,
                format(oldValue), format(newValue), authContext.username(), Instant.now()));
        log.info("Zabiljezena promjena priloga na zapisu id={} stupac={}", surveyId, columnKey);
    }

    /**
     * Vrijednost je izgubljena zbog izmjene SHEME - promjene tipa ili brisanja stupca.
     *
     * Bez ovoga je izmjena sheme bila jedini nacin da podatak nestane bez traga: dnevnik je
     * biljezio "stupac izmijenjen" i broj zahvacenih zapisa, ali se iz nijednog retka nije
     * moglo vidjeti STO je u njemu izgubljeno. Povijest koja presuti brisanje ne dokazuje nista.
     *
     * Preimenovanje stupca ovamo NE dolazi - vrijednost je ostala, samo pod drugim kljucem.
     */
    @Transactional
    public void valueCleared(Long surveyId, Long templateId, Long companyId,
                             String columnKey, Object oldValue) {
        String formatted = format(oldValue);
        if (formatted == null) {
            // prazna vrijednost nije nista izgubila
            return;
        }
        repository.save(new RecordChange(surveyId, templateId, companyId, columnKey,
                formatted, null, authContext.username(), Instant.now()));
        log.info("Zabiljezena izgubljena vrijednost stupca {} na zapisu id={}", columnKey, surveyId);
    }

    /**
     * Povijest jednog zapisa, najnovije prvo.
     *
     * Pripadnost zapisa firmi je vec provjerena kod pozivatelja
     * ({@code SurveyResultService.findOwnedOrThrow}) - ovaj razred je ne provjerava ponovno,
     * jednako kao sto {@code CodebookItemRepository} racuna na provjeru u svom servisu.
     *
     * @param schema trenutna shema obrasca, samo za razrjesavanje naziva stupaca
     */
    public List<RecordChangeResponse> history(Long surveyId, List<ColumnDefinitionResponse> schema) {
        return repository.findBySurveyIdOrderByChangedAtDescIdDesc(surveyId).stream()
                .map(change -> RecordChangeResponse.from(change, labelFor(change.getColumnKey(), schema)))
                .toList();
    }

    /** Zapis se brise - njegova povijest nema vise na sto pokazivati. */
    @Transactional
    public void forSurvey(Long surveyId) {
        repository.deleteBySurveyId(surveyId);
    }

    /** Brise se cijeli obrazac, sa svim zapisima. */
    @Transactional
    public void forTemplate(Long templateId) {
        repository.deleteByTemplateId(templateId);
    }

    /** Firma se trajno prazni. */
    @Transactional
    public void forCompany(Long companyId) {
        repository.deleteByCompanyId(companyId);
    }

    /**
     * Promjene izmedu dva stanja retka.
     *
     * Prolazi se kroz UNIJU kljuceva oba stanja, a ne kroz shemu: tako se uhvati i polje koje
     * je vrijednost izgubilo, i polje stupca koji je u meduvremenu maknut iz sheme. Shema bi
     * oboje presutjela - a upravo su to promjene koje netko trazi kad otvori povijest.
     */
    private List<RecordChange> diff(Long surveyId, Long templateId, Long companyId,
                                    Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> from = before != null ? before : Map.of();
        Map<String, Object> to = after != null ? after : Map.of();

        Instant now = Instant.now();
        String username = authContext.username();
        List<RecordChange> changes = new ArrayList<>();

        for (String key : union(from.keySet(), to.keySet())) {
            String oldValue = format(from.get(key));
            String newValue = format(to.get(key));
            // usporeduje se PRIKAZANI oblik, ne sirova vrijednost: 1 i 1.0 su za citatelja
            // ista vrijednost, a zapis "promijenjeno iz 1 u 1" nije trag nego smece
            if (Objects.equals(oldValue, newValue)) {
                continue;
            }
            changes.add(new RecordChange(surveyId, templateId, companyId, key,
                    oldValue, newValue, username, now));
        }
        return changes;
    }

    /** Kljucevi oba stanja, bez ponavljanja i stabilnog redoslijeda. */
    private Set<String> union(Set<String> first, Set<String> second) {
        Set<String> keys = new LinkedHashSet<>(first);
        keys.addAll(second);
        return keys;
    }

    /**
     * Vrijednost u obliku u kojem je covjek vidi.
     *
     * Visevrijednosni stupac (sifrarnik s visestrukim odabirom) nosi NIZ sifara; u tragu stoji
     * kao popis odvojen zarezom, jer bi "[A, B]" bio oblik spremanja, a ne ono sto je na ekranu.
     *
     * @return tekst, ili null kad vrijednosti nema (prazan tekst je isto "nema")
     */
    private String format(Object value) {
        if (value == null) {
            return null;
        }
        String text = value instanceof Collection<?> items
                ? items.stream().map(RecordChangeService::item).reduce((a, b) -> a + ", " + b).orElse("")
                : String.valueOf(value);
        if (text.isBlank()) {
            return null;
        }
        return text.length() > MAX_VALUE
                ? text.substring(0, MAX_VALUE - TRUNCATED.length()) + TRUNCATED
                : text;
    }

    /**
     * Jedan clan popisa u obliku u kojem ga covjek vidi.
     *
     * Poveznica je jedina vrijednost koja je MAPA, a ne broj ili tekst, pa bi bez ovoga u
     * tragu stajalo "{url=https://..., name=Politika}" - oblik spremanja, ne ono sto je bilo
     * na ekranu. U tragu ostaje i naziv i adresa: naziv se s vremenom mijenja, a adresa je
     * ono sto se poslije provjerava.
     */
    private static String item(Object value) {
        if (value instanceof Map<?, ?> map && map.get("url") != null) {
            Object name = map.get("name");
            String url = String.valueOf(map.get("url"));
            return name == null || String.valueOf(name).isBlank() ? url : name + " (" + url + ")";
        }
        return String.valueOf(value);
    }

    /** Naziv stupca iz trenutne sheme; kad stupca vise nema, ostaje sam kljuc. */
    private String labelFor(String columnKey, List<ColumnDefinitionResponse> schema) {
        for (ColumnDefinitionResponse column : schema) {
            if (column.columnKey().equals(columnKey)) {
                return column.displayLabel();
            }
        }
        return columnKey;
    }
}
