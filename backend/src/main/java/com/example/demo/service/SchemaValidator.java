package com.example.demo.service;

import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.exception.SchemaValidationException;
import com.example.demo.model.CodebookItem;
import com.example.demo.model.ColumnOptions;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.SurveyResultRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Provjerava odgovaraju li podaci retka shemi njegovog obrasca.
 *
 * Zove se ISKLJUCIVO pri upisu. Citanje se namjerno ne validira: shema se s
 * vremenom mijenja, pa bi provjera na citanju znacila da stariji retci odjednom
 * prestanu raditi zbog izmjene koja s njima nema veze.
 *
 * Provjere idu u dva prolaza jer nisu iste prirode:
 *   1. po POSLANIM poljima - odgovara li vrijednost tipu stupca,
 *   2. po SHEMI - {@code required} i {@code unique}. Ta pravila govore o polju kojeg
 *      u poslanim podacima mozda uopce NEMA, pa ih prvi prolaz ne bi ni pogledao.
 *
 * {@code unique} se ne da provjeriti iz jednog retka - zato validator dobiva
 * {@link UniqueValueLookup}, pitanje koje umjesto njega odgovara onaj tko ima pristup
 * ostalim redcima. Nekad su se predavali sami redci, pa je svako spremanje ucitavalo cijeli
 * obrazac u memoriju; sada na isto pitanje baza odgovara jednim upitom.
 *
 * Pravilo jednakosti pritom mora ostati isto na oba mjesta - {@link #sameValue} opisuje koje
 * je, a upit ga preslikava ({@code lower(btrim(...))}, brojevi po vrijednosti).
 *
 * Stupac tipa "codebook" tjera validator u bazu, jer dopustene vrijednosti vise nisu u
 * samoj shemi nego u sifrarniku. Ovdje se NE provjerava smije li pozivatelj taj sifrarnik
 * vidjeti - to je pitanje pristupa i rjesava se pri deklariranju stupca; ovdje je tema
 * samo odgovara li vrijednost shemi.
 *
 * Isto vrijedi i za stupac tipa "reference": provjerava se postoji li ciljani zapis i pripada
 * li ciljanom obrascu, ali ne i cija je to firma. Ciljani obrazac je pri deklariranju stupca
 * vec potvrden kao firmin, a zapis ne moze pripadati obrascu jedne firme a podacima druge.
 */
@Component
public class SchemaValidator {

    /** Granica adrese - preglednici odbijaju puno duze, a jsonb dokument ne treba nositi roman. */
    private static final int MAX_URL_LENGTH = 2000;

    /** Naziv poveznice stoji u celiji dijaloga, pa dulji od ovoga ionako nitko ne bi procitao. */
    private static final int MAX_LINK_NAME_LENGTH = 200;

    private final CodebookItemRepository codebookItemRepository;
    private final SurveyResultRepository surveyResultRepository;

    public SchemaValidator(CodebookItemRepository codebookItemRepository,
                           SurveyResultRepository surveyResultRepository) {
        this.codebookItemRepository = codebookItemRepository;
        this.surveyResultRepository = surveyResultRepository;
    }

    /** Bez provjere jedinstvenosti - koristi se ondje gdje ona ne moze biti tema. */
    public void validate(Map<String, Object> data, List<ColumnDefinitionResponse> schema) {
        validate(data, schema, UniqueValueLookup.NONE);
    }

    /**
     * @param data   podaci retka koji se sprema
     * @param schema stupci obrasca kojem redak pripada
     * @param taken  odgovara ima li NEKI DRUGI redak istog obrasca zadanu vrijednost; redak
     *               koji se upravo sprema mora biti izostavljen, inace bi izmjena sama sebi
     *               bila duplikat
     */
    public void validate(Map<String, Object> data,
                         List<ColumnDefinitionResponse> schema,
                         UniqueValueLookup taken) {
        Map<String, Object> safe = data != null ? data : Map.of();

        Map<String, ColumnDefinitionResponse> byKey = new LinkedHashMap<>();
        for (ColumnDefinitionResponse column : schema) {
            byKey.put(column.columnKey(), column);
        }

        List<String> errors = new ArrayList<>();
        collectValueProblems(safe, byKey, errors);
        collectRuleProblems(safe, schema, taken, errors);

        if (!errors.isEmpty()) {
            throw new SchemaValidationException(errors);
        }
    }

    /** Prvi prolaz: svaka poslana vrijednost mora pripadati poznatom stupcu i njegovom tipu. */
    private void collectValueProblems(Map<String, Object> data,
                                      Map<String, ColumnDefinitionResponse> byKey,
                                      List<String> errors) {
        for (Map.Entry<String, Object> field : data.entrySet()) {
            ColumnDefinitionResponse column = byKey.get(field.getKey());
            if (column == null) {
                errors.add("unknown column \"" + field.getKey() + "\"");
                continue;
            }
            // Nepopunjeno polje samo po sebi nije greska - ako je stupac obavezan, uhvatit
            // ce ga drugi prolaz. Ovdje se preskace sve sto je prazno, ne samo null: forma
            // za nepopunjeno polje salje prazan tekst (ili prazan popis), a takav bi kod
            // sifrarnickog stupca inace bio odbijen kao "nepostojeca sifra" - i to na polju
            // koje korisnik nije ni dirao.
            if (isEmpty(field.getValue())) {
                continue;
            }
            String problem = check(column, field.getValue());
            if (problem != null) {
                errors.add(problem(field.getKey(), problem));
            }
        }
    }

    /** Drugi prolaz: pravila koja se ticu stupca kao takvog, bez obzira je li vrijednost poslana. */
    private void collectRuleProblems(Map<String, Object> data,
                                     List<ColumnDefinitionResponse> schema,
                                     UniqueValueLookup taken,
                                     List<String> errors) {
        for (ColumnDefinitionResponse column : schema) {
            Object value = data.get(column.columnKey());

            if (column.required() && isEmpty(value)) {
                errors.add(problem(column.columnKey(), "is required"));
                continue;
            }
            // prazno se ne provjerava na jedinstvenost - vise redaka smije ostaviti isti stupac prazan
            if (column.unique() && !isEmpty(value) && taken.isTaken(column.columnKey(), value)) {
                errors.add(problem(column.columnKey(), "value \"" + value
                        + "\" already exists in another record and this column must be unique"));
            }
        }
    }

    /**
     * Prva vrijednost koja se u popisu pojavljuje dva puta, po istom pravilu jednakosti
     * kojim se mjeri i {@code unique} pri upisu retka. Sluzi za provjeru moze li se
     * jedinstvenost uopce ukljuciti na stupac koji vec ima upisane vrijednosti.
     *
     * @return duplicirana vrijednost, ili null ako su sve razlicite
     */
    public Object findDuplicate(List<Object> values) {
        List<Object> seen = new ArrayList<>();
        for (Object value : values) {
            if (isEmpty(value)) {
                continue;
            }
            for (Object earlier : seen) {
                if (sameValue(value, earlier)) {
                    return value;
                }
            }
            seen.add(value);
        }
        return null;
    }

    /**
     * Jesu li dvije vrijednosti "ista" vrijednost za potrebe jedinstvenosti.
     * Tekst se usporeduje bez obzira na velika slova i rubne razmake - "Split " i "split"
     * su korisniku isti grad, pa bi ih propustiti kao razlicite promasilo smisao pravila.
     * Brojevi se usporeduju po vrijednosti jer isti broj iz JSON-a moze stici kao
     * Integer ili Double (5 i 5.0).
     */
    private boolean sameValue(Object a, Object b) {
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof String left && b instanceof String right) {
            return left.trim().equalsIgnoreCase(right.trim());
        }
        if (a instanceof Number left && b instanceof Number right) {
            return left.doubleValue() == right.doubleValue();
        }
        return Objects.equals(a, b);
    }

    /** Prazno = nepopunjeno: nedostaje, prazan/sam razmak tekst, ili prazan popis odabranih sifara. */
    private boolean isEmpty(Object value) {
        if (value instanceof List<?> list) {
            return list.isEmpty();
        }
        return value == null || (value instanceof String text && text.isBlank());
    }

    /**
     * Provjera jedne vrijednosti protiv jednog stupca, van konteksta cijelog retka.
     * Koristi se pri izmjeni tipa stupca: vrijednost koja novom tipu ne odgovara se
     * iz zapisa cisti. {@code null} (nepopunjeno) uvijek prolazi.
     *
     * @return opis problema, ili null ako je vrijednost u redu
     */
    public String fieldProblem(ColumnDefinitionResponse column, Object value) {
        return value == null ? null : check(column, value);
    }

    /**
     * Zadana vrijednost stupca pretvorena iz teksta u vrijednost onog tipa u kojem se
     * sprema u redak. Prazna zadana vrijednost je null (stupac nema default).
     *
     * Cuva se kao tekst jer je i unosi se kao tekst (jedno polje u Editoru za sve tipove);
     * ovdje se jednom pretvara, da ostatak koda ne mora znati kako je zapisana.
     *
     * Kod visevrijednosnog sifrarnickog stupca zadana vrijednost je jedna sifra, ali se u
     * redak sprema kao popis od jednog clana - inace bi predpopunjeni redak imao oblik
     * kakav taj stupac inace nema.
     *
     * @throws IllegalArgumentException ako tekst ne odgovara tipu - pozivatelj to mora
     *                                  sprijeciti prije spremanja sheme ({@link #defaultValueProblem})
     */
    public Object parseDefaultValue(ColumnDefinitionResponse column) {
        String raw = column.defaultValue();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (column.isMultiValued()) {
            return List.of(text);
        }
        return switch (column.columnType()) {
            case "number" -> parseNumber(text);
            default -> text;
        };
    }

    /**
     * Odgovara li zadana vrijednost stupca njegovom tipu i dopustenim vrijednostima.
     *
     * Opis problema je hrvatski, za razliku od poruka o podacima retka: ovu vidi onaj tko
     * deklarira shemu, kao dio recenice o stupcu koji upravo definira.
     *
     * @return opis problema, ili null ako je zadana vrijednost u redu (ili je nema)
     */
    public String defaultValueProblem(ColumnDefinitionResponse column) {
        String raw = column.defaultValue();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            // broj se provjerava samim pretvaranjem, tekst prolazi uvijek
            parseDefaultValue(column);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        return switch (column.columnType()) {
            case "number" -> numberProblemHr(column, parseNumber(text));
            case "date" -> dateProblemHr(column, text);
            case "string" -> patternProblemHr(column, text);
            case "codebook" -> codebookDefaultProblem(column, text);
            default -> null;
        };
    }

    /**
     * Zadana vrijednost sifrarnickog stupca je SIFRA stavke, ne njezin naziv - u redak se
     * sprema sifra, pa bi naziv predpopunio nesto sto backend pri spremanju odbija.
     *
     * Za razliku od unosa u redak, ovdje se trazi AKTIVNA stavka: iskljucena se namjerno
     * ne nudi za nove unose, a zadana vrijednost je upravo to.
     */
    private String codebookDefaultProblem(ColumnDefinitionResponse column, String text) {
        if (column.codebookId() == null) {
            return null;
        }
        List<CodebookItem> items = codebookItemRepository.findByCodebookIdOrderBySortOrderAsc(column.codebookId());
        for (CodebookItem item : items) {
            if (item.getCode().equals(text)) {
                return item.isActive() ? null
                        : "šifra \"" + text + "\" je isključena stavka šifrarnika, pa se ne može predpopuniti";
            }
        }
        // stupac koji sam puni sifrarnik smije imati zadanu vrijednost koje jos nema -
        // ona se pri prvom unosu upravo tako i stvara
        if (column.options().allowNewValues()) {
            return null;
        }
        return "\"" + text + "\" nije šifra iz odabranog šifrarnika"
                + (items.isEmpty() ? " (šifrarnik još nema stavki)" : "");
    }

    private boolean isIsoDate(String text) {
        try {
            LocalDate.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private boolean isIsoDateTime(String text) {
        try {
            LocalDateTime.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private Number parseNumber(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ignored) {
            try {
                return Double.valueOf(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("\"" + text + "\" nije broj");
            }
        }
    }

    /** @return opis problema, ili null ako je vrijednost u redu */
    private String check(ColumnDefinitionResponse column, Object value) {
        return switch (column.columnType()) {
            case "string" -> checkString(column, value);
            case "number" -> checkNumber(column, value);
            case "date" -> checkDate(column, value);
            case "codebook" -> checkCodebook(column, value);
            case "reference" -> checkReference(column, value);
            case "link" -> checkLinks(value);
            // Rezultat formule racuna server, pa je jedini ispravan sadrzaj broj. Stupci
            // "button" i "file" nemaju vrijednost u zapisu (prilozi zive u vlastitoj tablici),
            // pa se sve poslano ionako odbacuje pri upisu.
            case "formula" -> value instanceof Number ? null : expected("number", value);
            case "button", "file" -> null;
            // Tip koji ovaj razred ne poznaje je problem sheme, ne podataka -
            // upis se zato ne blokira.
            default -> null;
        };
    }

    private String checkString(ColumnDefinitionResponse column, Object value) {
        if (!(value instanceof String text)) {
            return expected("text", value);
        }
        return patternProblem(column, text);
    }

    /**
     * Broj uz ogranicenja iz postavki stupca: cijeli broj, raspon i uzorak.
     *
     * Uzorak se primjenjuje na ISPISANI oblik broja, jer regularni izraz radi nad tekstom -
     * tako "^\\d{5}$" na broju znaci "petoznamenkast", sto je i namjera onoga tko ga upisuje.
     */
    private String checkNumber(ColumnDefinitionResponse column, Object value) {
        if (!(value instanceof Number number)) {
            return expected("number", value);
        }
        return numberProblem(column, number);
    }

    private String numberProblem(ColumnDefinitionResponse column, Number number) {
        ColumnOptions options = column.options();
        BigDecimal decimal = new BigDecimal(number.toString());

        if (options.isInteger() && decimal.stripTrailingZeros().scale() > 0) {
            return "expected a whole number, got " + number;
        }
        if (options.min() != null && decimal.compareTo(options.min()) < 0) {
            return "value " + number + " is below the minimum " + options.min();
        }
        if (options.max() != null && decimal.compareTo(options.max()) > 0) {
            return "value " + number + " is above the maximum " + options.max();
        }
        return patternProblem(column, decimal.stripTrailingZeros().toPlainString());
    }

    /** Ista provjera, ali s porukom na hrvatskom - za zadanu vrijednost koju upisuje urednik sheme. */
    private String numberProblemHr(ColumnDefinitionResponse column, Number number) {
        ColumnOptions options = column.options();
        BigDecimal decimal = new BigDecimal(number.toString());

        if (options.isInteger() && decimal.stripTrailingZeros().scale() > 0) {
            return "\"" + number + "\" nije cijeli broj";
        }
        if (options.min() != null && decimal.compareTo(options.min()) < 0) {
            return "\"" + number + "\" je manje od najmanje dopuštene vrijednosti " + options.min();
        }
        if (options.max() != null && decimal.compareTo(options.max()) > 0) {
            return "\"" + number + "\" je veće od najveće dopuštene vrijednosti " + options.max();
        }
        return patternProblemHr(column, decimal.stripTrailingZeros().toPlainString());
    }

    /**
     * Datum ili datum s vremenom, ovisno o postavci stupca.
     *
     * Oboje se sprema kao tekst u ISO obliku, jer jsonb nema tip za datum - a ISO je jedini
     * oblik koji se i sortira i usporeduje kao obican niz znakova.
     */
    private String checkDate(ColumnDefinitionResponse column, Object value) {
        boolean withTime = column.options().isDateTime();
        if (!(value instanceof String text)) {
            return expected(withTime ? "date and time as text (YYYY-MM-DDTHH:MM)" : "date as text (YYYY-MM-DD)", value);
        }
        if (withTime) {
            return isIsoDateTime(text) ? null
                    : "\"" + text + "\" is not a date and time in YYYY-MM-DDTHH:MM format";
        }
        return isIsoDate(text) ? null : "\"" + text + "\" is not a date in YYYY-MM-DD format";
    }

    private String dateProblemHr(ColumnDefinitionResponse column, String text) {
        if (column.options().isDateTime()) {
            return isIsoDateTime(text) ? null
                    : "\"" + text + "\" nije datum i vrijeme u obliku YYYY-MM-DDTHH:MM";
        }
        return isIsoDate(text) ? null : "\"" + text + "\" nije datum u obliku YYYY-MM-DD";
    }

    /**
     * Vrijednost sifrarnickog stupca mora biti sifra stavke tog sifrarnika.
     *
     * Visevrijednosni stupac nosi POPIS sifara umjesto jedne. Popis se provjerava clan po
     * clan istim pravilom - razlika je samo u omotu, ne u tome sto se smije upisati.
     *
     * Prihvaca se i ISKLJUCENA stavka: iskljucivanje znaci "ne nudi vise za nove unose",
     * a ne "ponisti sve sto je vec upisano". Redak koji je tu sifru dobio dok je bila
     * aktivna mora se moci spremiti i poslije - inace bi iskljucivanje jedne stavke
     * zakljucalo uredivanje starih zapisa.
     *
     * Stupac bez sifrarnika je greska SHEME, ne podataka, pa upis ne blokira - isto
     * kao i tip koji ovaj razred ne poznaje.
     */
    private String checkCodebook(ColumnDefinitionResponse column, Object value) {
        if (column.isMultiValued()) {
            if (!(value instanceof List<?> list)) {
                return expected("list of codes from the codebook", value);
            }
            for (Object item : list) {
                String problem = checkSingleCode(column, item);
                if (problem != null) {
                    return problem;
                }
            }
            return null;
        }
        return checkSingleCode(column, value);
    }

    private String checkSingleCode(ColumnDefinitionResponse column, Object value) {
        if (!(value instanceof String text)) {
            return expected("code from the codebook", value);
        }
        if (column.codebookId() == null) {
            return null;
        }
        Set<String> codes = codesOf(column.codebookId());
        if (codes.contains(text)) {
            return null;
        }
        return "\"" + text + "\" is not a code in the codebook of this column"
                + (codes.isEmpty() ? " (the codebook has no items)" : "");
    }

    /**
     * Vrijednost referentnog stupca mora biti ID postojeceg zapisa CILJANOG obrasca.
     *
     * Visevrijednosni stupac nosi POPIS id-eva; provjerava se clan po clan istim pravilom, kao
     * i kod sifrarnika - razlika je samo u omotu.
     *
     * Baza ovdje ne pomaze: veza je broj unutar jsonb dokumenta, a ne stupac, pa Postgres ne
     * zna da je to strani kljuc. Cijela provjera referencijalnog integriteta zato pada na ovaj
     * razred i na pravila brisanja u {@code ReferenceLookup}.
     *
     * Stupac bez ciljanog obrasca je greska SHEME, ne podataka, pa upis ne blokira - isto
     * kao i sifrarnicki stupac bez sifrarnika.
     */
    /**
     * Popis poveznica: {@code [{"url": "...", "name": "..."}]}, gdje je naziv neobavezan.
     *
     * Zasto se sadrzaj provjerava OVDJE, a ne samo u pregledniku: adresu upisuje korisnik, a
     * otvara je svatko tko poslije otvori taj zapis. Bez provjere bi {@code javascript:...}
     * upisan u redak zavrsio kao poveznica koju netko drugi klikne - unos podataka time
     * postaje nacin da se izvrsi kod kod kolege. Preglednik je zadnja crta, ali ne i jedina.
     */
    private String checkLinks(Object value) {
        if (!(value instanceof List<?> list)) {
            return expected("list of links", value);
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> link)) {
                return expected("link object", item);
            }
            Object url = link.get("url");
            if (!(url instanceof String text) || text.isBlank()) {
                return "link without an address";
            }
            String lower = text.trim().toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return "link must start with http:// or https:// (was: " + text.trim() + ")";
            }
            if (text.trim().length() > MAX_URL_LENGTH) {
                return "link is longer than " + MAX_URL_LENGTH + " characters";
            }
            Object name = link.get("name");
            if (name != null && !(name instanceof String)) {
                return expected("link name (text)", name);
            }
            if (name instanceof String label && label.length() > MAX_LINK_NAME_LENGTH) {
                return "link name is longer than " + MAX_LINK_NAME_LENGTH + " characters";
            }
            for (Object key : link.keySet()) {
                if (!"url".equals(key) && !"name".equals(key)) {
                    return "unknown field in link: " + key;
                }
            }
        }
        return null;
    }

    private String checkReference(ColumnDefinitionResponse column, Object value) {
        if (column.isMultiValued()) {
            if (!(value instanceof List<?> list)) {
                return expected("list of record ids", value);
            }
            for (Object item : list) {
                String problem = checkSingleReference(column, item);
                if (problem != null) {
                    return problem;
                }
            }
            return null;
        }
        return checkSingleReference(column, value);
    }

    private String checkSingleReference(ColumnDefinitionResponse column, Object value) {
        if (!(value instanceof Number number)) {
            return expected("record id (number)", value);
        }
        Long targetTemplateId = column.options().targetTemplateId();
        if (targetTemplateId == null) {
            return null;
        }
        long id = number.longValue();
        if (surveyResultRepository.existsByIdAndTemplateId(id, targetTemplateId)) {
            return null;
        }
        return "record " + id + " does not exist in the linked form";
    }

    /**
     * Uzorak (regularni izraz) koji vrijednost mora zadovoljiti.
     *
     * Neispravan uzorak se ovdje PRESKACE umjesto da srusi upis: shemu je vec odbio
     * {@code validateDefinition}, pa ako je takav uzorak nekako zavrsio u bazi, bolje je
     * pustiti upis nego zakljucati cijeli obrazac zbog jednog stupca.
     */
    private String patternProblem(ColumnDefinitionResponse column, String text) {
        Pattern pattern = compiled(column.options().pattern());
        if (pattern == null || pattern.matcher(text).matches()) {
            return null;
        }
        return "value \"" + text + "\" does not match the required pattern " + column.options().pattern();
    }

    private String patternProblemHr(ColumnDefinitionResponse column, String text) {
        Pattern pattern = compiled(column.options().pattern());
        if (pattern == null || pattern.matcher(text).matches()) {
            return null;
        }
        return "\"" + text + "\" ne odgovara zadanom uzorku " + column.options().pattern();
    }

    private Pattern compiled(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    private Set<String> codesOf(Long codebookId) {
        Set<String> codes = new LinkedHashSet<>();
        for (CodebookItem item : codebookItemRepository.findByCodebookIdOrderBySortOrderAsc(codebookId)) {
            codes.add(item.getCode());
        }
        return codes;
    }

    private String expected(String expected, Object value) {
        return "expected " + expected + ", got " + value.getClass().getSimpleName();
    }

    private String problem(String columnKey, String description) {
        return "column \"" + columnKey + "\": " + description;
    }
}
