package com.example.demo.business;

import com.example.demo.model.SurveyResult;
import com.example.demo.repository.SurveyQueryRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.service.SurveyFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUSINESS test uz PRAVU bazu - pretraga, sortiranje i stranicenje nad jsonb dokumentom.
 *
 * Ovo se namjerno ne testira mockovima. Sve zamke ovog zadatka su u samom SQL-u i mock bi ih
 * sve propustio: da {@code data ->> :kljuc} bez casta padne na "operator is not unique", da
 * {@code ::numeric} sruse cijeli upit zbog jednog retka s tekstom, da se brojevi sortiraju kao
 * tekst (100 ispred 9), da LIKE procita korisnikov "%" kao uzorak, i da stranica bez
 * jednoznacnog poretka vraca isti redak dvaput. Mock bi na sve to rekao "prosao".
 *
 * {@code @Transactional} na testu znaci da se sve upisano na kraju ponisti - razvojna baza
 * ostaje kakva je bila.
 */
@Tag("business")
@SpringBootTest(properties = "app.jwt.secret=tajna-samo-za-testove-dovoljno-duga-32+")
@Transactional
class SurveySearchSqlTest {

    /** Obrazac koji ne postoji u razvojnoj bazi - zapisi ovog testa nikome ne smetaju. */
    private static final long TEMPLATE_ID = 999_001L;
    private static final long OTHER_TEMPLATE_ID = 999_002L;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private SurveyQueryRepository queryRepository;

    @Autowired
    private SurveyResultRepository repository;

    private Long idOf(String label) {
        return ids.get(label);
    }

    private final Map<String, Long> ids = new HashMap<>();

    private void row(String label, long templateId, Map<String, Object> data) {
        SurveyResult survey = new SurveyResult();
        survey.setCompanyId(1L);
        survey.setTemplateId(templateId);
        survey.setData(new HashMap<>(data));
        ids.put(label, repository.save(survey).getId());
    }

    @BeforeEach
    void seed() {
        row("zagreb", TEMPLATE_ID, Map.of("grad", "Zagreb", "cijena", 100, "sifra", "ZG"));
        row("split", TEMPLATE_ID, Map.of("grad", "Split", "cijena", 9, "sifra", "ST"));
        row("stotinu", TEMPLATE_ID, Map.of("grad", "Zadar", "cijena", "nije broj", "sifra", "ZD"));
        row("posto", TEMPLATE_ID, Map.of("grad", "100% siguran", "cijena", 1));
        row("popis", TEMPLATE_ID, Map.of("gradovi", List.of("ZG", "ST")));
        row("tudi", OTHER_TEMPLATE_ID, Map.of("grad", "Zagreb"));

        // Zapisi se ISPIRU u bazu i mice ih se iz konteksta. Bez `clear()` Hibernate na svaki
        // pogodeni id vraca objekt koji jos drzi u memoriji - onaj koji je test upravo sam
        // sastavio - pa se nikad ne procita ono sto je Postgres stvarno spremio. Upravo tako je
        // brisanje priloga prolazilo kroz zelen test i padalo u aplikaciji (vidi
        // AttachmentStorageTest.removeContentWorksWithoutLoadedEntity).
        em.flush();
        em.clear();
    }

    private List<Long> found(List<SurveyFilter> filters, String sortKey, boolean numeric, boolean desc) {
        return queryRepository.find(TEMPLATE_ID, filters, sortKey, numeric, desc, 0, 50)
                .stream().map(SurveyResult::getId).toList();
    }

    // ===================== IZOLACIJA I BROJANJE =====================

    @Test
    @DisplayName("stranica i broj se odnose samo na zapise trazenog obrasca")
    void countsAndReadsOnlyOwnTemplate() {
        assertThat(queryRepository.count(TEMPLATE_ID, List.of())).isEqualTo(5);
        assertThat(found(List.of(), null, false, false)).doesNotContain(idOf("tudi"));
    }

    // ===================== SORTIRANJE =====================

    /**
     * Zamka broj jedan: {@code data ->> 'cijena'} vraca TEKST, pa bi "100" ispalo ispred "9".
     * Brojcani stupac se zato sortira kao broj.
     */
    @Test
    @DisplayName("brojcani stupac se sortira po vrijednosti, ne kao tekst")
    void numericSortIsNotTextSort() {
        List<Long> ordered = found(List.of(), "cijena", true, false);

        assertThat(ordered).startsWith(idOf("posto"), idOf("split"), idOf("zagreb"));
    }

    /**
     * Zamka broj dva: {@code (data->>'cijena')::numeric} pada na retku u kojem je pod tim
     * kljucem ostao tekst (stupac je promijenio tip, a zapis zaostao) - i to tako da se ekran
     * VISE NE OTVORI. Neispravna vrijednost mora ispasti na kraj, a ne srusiti upit.
     */
    @Test
    @DisplayName("redak s vrijednoscu koja nije broj ne rusi sortiranje po broju")
    void nonNumericValueDoesNotBreakNumericSort() {
        List<Long> ordered = found(List.of(), "cijena", true, false);

        assertThat(ordered).hasSize(5).endsWith(idOf("stotinu"), idOf("popis"));
    }

    @Test
    @DisplayName("tekstualni stupac se sortira bez obzira na velika slova, prazno na kraju")
    void textSortIgnoresCaseAndPutsEmptyLast() {
        List<Long> ordered = found(List.of(), "grad", false, false);

        // "100% siguran", "Split", "Zadar", "Zagreb", pa redak koji taj stupac uopce nema
        assertThat(ordered).startsWith(idOf("posto"), idOf("split"), idOf("stotinu"), idOf("zagreb"));
        assertThat(ordered).endsWith(idOf("popis"));
    }

    /**
     * Prazan tekst je za bazu obicna vrijednost, i to najmanja - pa {@code NULLS LAST} na njega
     * ne djeluje i redak ispliva na VRH. Za citatelja je "prazno" i "nema kljuca" isto stanje,
     * pa oboje pripada na dno.
     *
     * Nadeno rucnom provjerom, u dijalogu odabira zapisa: zapisi bez naziva stajali
     * su ispred svih imenovanih.
     */
    @Test
    @DisplayName("prazan tekst tone na dno kao i redak koji stupac uopce nema")
    void blankTextSortsWithMissingValues() {
        row("prazno", TEMPLATE_ID, Map.of("grad", ""));
        row("razmaci", TEMPLATE_ID, Map.of("grad", "   "));

        List<Long> ordered = found(List.of(), "grad", false, false);

        assertThat(ordered).startsWith(idOf("posto"), idOf("split"), idOf("stotinu"), idOf("zagreb"));
        assertThat(ordered).endsWith(idOf("popis"), idOf("prazno"), idOf("razmaci"));
    }

    /**
     * Bez jednoznacnog poretka baza smije dva retka s istom vrijednoscu vratiti bilo kojim
     * redoslijedom - pa se uz stranicenje isti zapis zna pojaviti na dvije stranice ili nestati
     * s obje. Zato iza sortiranja uvijek ide id.
     */
    @Test
    @DisplayName("zapisi s istom vrijednoscu imaju stalan poredak, pa se stranice ne preklapaju")
    void equalValuesKeepStableOrder() {
        row("isti1", TEMPLATE_ID, Map.of("grad", "Isti"));
        row("isti2", TEMPLATE_ID, Map.of("grad", "Isti"));

        List<Long> first = found(List.of(), "grad", false, false);
        List<Long> second = found(List.of(), "grad", false, false);

        assertThat(first).isEqualTo(second);
        assertThat(first.indexOf(idOf("isti1"))).isLessThan(first.indexOf(idOf("isti2")));
    }

    // ===================== STRANICENJE =====================

    @Test
    @DisplayName("stranica vraca svoj dio, a ukupan broj ostaje broj SVIH pogodaka")
    void pagingReturnsSliceAndTotalStaysWhole() {
        List<SurveyResult> firstPage = queryRepository.find(TEMPLATE_ID, List.of(), "grad", false, false, 0, 2);
        List<SurveyResult> secondPage = queryRepository.find(TEMPLATE_ID, List.of(), "grad", false, false, 1, 2);

        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(2);
        assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);
        assertThat(queryRepository.count(TEMPLATE_ID, List.of())).isEqualTo(5);
    }

    // ===================== PRETRAGA =====================

    @Test
    @DisplayName("tekstualna pretraga trazi dio vrijednosti, bez obzira na velika slova")
    void textFilterIsContainsAndCaseInsensitive() {
        assertThat(found(List.of(SurveyFilter.text("grad", "zag")), null, false, false))
                .containsExactly(idOf("zagreb"));
    }

    /**
     * Zamka: {@code %} i {@code _} su za LIKE uzorak, a korisnik ih upisuje kao obican tekst.
     * Servis ih pobjegne prije nego dodu ovamo - ovdje se provjerava da pobjegnuti oblik radi,
     * tj. da "100%" nalazi bas taj tekst, a ne sve sto pocinje sa "100".
     */
    @Test
    @DisplayName("posto i podvlaka u upitu su obican tekst, a ne uzorak")
    void likeSpecialCharactersAreLiteral() {
        row("stotka", TEMPLATE_ID, Map.of("grad", "100 kuna"));

        assertThat(found(List.of(SurveyFilter.text("grad", "100\\%")), null, false, false))
                .containsExactly(idOf("posto"));
    }

    @Test
    @DisplayName("brojcani filtar trazi vrijednost, pa 100 ne nalazi 1000")
    void numberFilterMatchesByValue() {
        row("tisucu", TEMPLATE_ID, Map.of("cijena", 1000));

        assertThat(found(List.of(SurveyFilter.number("cijena", new BigDecimal("100"))), null, false, false))
                .containsExactly(idOf("zagreb"));
    }

    @Test
    @DisplayName("vise filtara suzava rezultat - spojeni su s I")
    void filtersAreCombinedWithAnd() {
        assertThat(found(List.of(SurveyFilter.text("grad", "Zagreb"),
                SurveyFilter.number("cijena", new BigDecimal("100"))), null, false, false))
                .containsExactly(idOf("zagreb"));

        assertThat(found(List.of(SurveyFilter.text("grad", "Zagreb"),
                SurveyFilter.number("cijena", new BigDecimal("1"))), null, false, false)).isEmpty();
    }

    /**
     * Sifrarnicki stupac drzi jednu sifru ili POPIS sifara, ovisno o postavci. Uvjet mora
     * raditi za oba oblika bez grananja - inace se zaboravi osvjeziti kad stupac promijeni
     * postavku.
     */
    @Test
    @DisplayName("sifre se nalaze i kad stupac drzi jednu i kad drzi popis")
    void codeFilterWorksForSingleAndMultiValue() {
        assertThat(found(List.of(SurveyFilter.codes("sifra", List.of("ZG", "ST"))), null, false, false))
                .containsExactlyInAnyOrder(idOf("zagreb"), idOf("split"));

        assertThat(found(List.of(SurveyFilter.codes("gradovi", List.of("ST"))), null, false, false))
                .containsExactly(idOf("popis"));
    }

    // ===================== JEDINSTVENOST =====================

    @Test
    @DisplayName("jedinstvenost vidi tudu vrijednost, a vlastiti redak izostavlja")
    void existsSeesOthersAndSkipsItself() {
        assertThat(queryRepository.existsWithText(TEMPLATE_ID, null, "grad", "Zagreb")).isTrue();
        assertThat(queryRepository.existsWithText(TEMPLATE_ID, idOf("zagreb"), "grad", "Zagreb")).isFalse();
    }

    /** Isto pravilo koje vec vrijedi u {@code SchemaValidator}: "Split " i "split" su isto. */
    @Test
    @DisplayName("tekst se usporeduje bez razlike velikih slova i rubnih razmaka")
    void existsIgnoresCaseAndSurroundingSpaces() {
        assertThat(queryRepository.existsWithText(TEMPLATE_ID, null, "grad", "  split ")).isTrue();
    }

    /** Isto pravilo: 1 i 1.0 su isti broj, iako nisu isti niz znakova. */
    @Test
    @DisplayName("broj se usporeduje po vrijednosti, pa 100.0 pronade 100")
    void existsComparesNumbersByValue() {
        assertThat(queryRepository.existsWithNumber(TEMPLATE_ID, null, "cijena", new BigDecimal("100.0"))).isTrue();
        assertThat(queryRepository.existsWithNumber(TEMPLATE_ID, null, "cijena", new BigDecimal("101"))).isFalse();
    }

    @Test
    @DisplayName("jedinstvenost ne gleda preko granice obrasca")
    void existsStaysWithinTemplate() {
        assertThat(queryRepository.existsWithText(OTHER_TEMPLATE_ID, null, "grad", "Split")).isFalse();
    }

    // ===================== VEZE MEDU OBRASCIMA =====================

    /**
     * Ponuda za dijalog odabira BEZ upisanog teksta.
     *
     * Ovo je tocno onaj poziv koji se dogodi cim se dijalog otvori, i tocno ono sto je jednom
     * vec puklo: brojanje nema ORDER BY, pa se stupac za naziv u tom upitu uopce ne pojavljuje -
     * a parametar se svejedno vezao. JPA takav poziv odbija, pa je dijalog javljao "dohvacanje
     * nije uspjelo" prije nego sto je itko isao pretrazivati. Mock bi ovo propustio.
     */
    @Test
    @DisplayName("ponuda bez upisanog teksta vraca sve zapise obrasca")
    void optionsWithoutSearch() {
        // svi zapisi obrasca, ukljucujuci onaj koji pod kljucem naziva nema nista - i takav
        // se zapis smije odabrati, samo ce stajati kao "#12 (bez naziva)"
        assertThat(queryRepository.countOptions(TEMPLATE_ID, "grad", null)).isEqualTo(5);
        assertThat(queryRepository.findOptions(TEMPLATE_ID, "grad", null, 0, 50)).hasSize(5);
        // prazan upit se mora ponasati isto kao izostavljen
        assertThat(queryRepository.countOptions(TEMPLATE_ID, "grad", "  ")).isEqualTo(5);
    }

    /**
     * Ponuda se pregledava ocima, pa zapisi bez naziva pripadaju na dno - a upravo su plutali
     * na vrh (prazan tekst je za bazu najmanja vrijednost, vidi
     * {@link #blankTextSortsWithMissingValues}). Prvo sto se u dijalogu vidjelo bio je popis
     * bezimenih zapisa.
     */
    @Test
    @DisplayName("ponuda stavlja zapise bez naziva na dno popisa")
    void optionsPutNamelessLast() {
        row("bez-naziva", TEMPLATE_ID, Map.of("grad", ""));

        List<Long> ordered = queryRepository.findOptions(TEMPLATE_ID, "grad", null, 0, 50)
                .stream().map(SurveyResult::getId).toList();

        assertThat(ordered).startsWith(idOf("posto"));
        assertThat(ordered).endsWith(idOf("popis"), idOf("bez-naziva"));
    }

    @Test
    @DisplayName("ponuda trazi po nazivu ILI po broju zapisa")
    void optionsSearchMatchesLabelOrId() {
        assertThat(queryRepository.countOptions(TEMPLATE_ID, "grad", "zagr")).isEqualTo(1);

        long id = idOf("split");
        List<SurveyResult> byId = queryRepository.findOptions(TEMPLATE_ID, "grad", String.valueOf(id), 0, 50);
        assertThat(byId).extracting(SurveyResult::getId).containsExactly(id);
    }

    /** Sifra kojom se trazi po id-u mora biti TOCNA - inace bi "1" naslo 1, 10, 100 i 1000. */
    @Test
    @DisplayName("pretraga po broju zapisa trazi tocan broj, ne pocetak")
    void optionsIdSearchIsExact() {
        long id = idOf("split");
        assertThat(queryRepository.findOptions(TEMPLATE_ID, "grad", String.valueOf(id).substring(0, 1), 0, 50))
                .extracting(SurveyResult::getId)
                .doesNotContain(id);
    }

    @Test
    @DisplayName("ponuda ne prelazi granicu obrasca")
    void optionsStayWithinTemplate() {
        assertThat(queryRepository.countOptions(OTHER_TEMPLATE_ID, "grad", null))
                .isLessThan(queryRepository.countOptions(TEMPLATE_ID, "grad", null));
    }

    /**
     * Veza u jsonb-u stoji u dva oblika - kao broj i kao niz - i {@code @>} ih ne pokriva
     * jednim izrazom. Zato uvjet ima dva clana; ovaj test cuva oba.
     */
    @Test
    @DisplayName("veza se nalazi i kad stoji kao broj i kad stoji u popisu")
    void findsLinkInBothShapes() {
        long target = 777L;
        row("veza-broj", TEMPLATE_ID, Map.of("proces", target));
        row("veza-popis", TEMPLATE_ID, Map.of("kontrole", List.of(555, target, 999)));

        assertThat(queryRepository.countLinkedTo(TEMPLATE_ID, "proces", target)).isEqualTo(1);
        assertThat(queryRepository.countLinkedTo(TEMPLATE_ID, "kontrole", target)).isEqualTo(1);
        assertThat(queryRepository.findLinkedTo(TEMPLATE_ID, "kontrole", target, 20))
                .extracting(SurveyResult::getId)
                .containsExactly(idOf("veza-popis"));
    }

    /** Broj koji je slucajno isti, ali u drugom stupcu, nije veza. */
    @Test
    @DisplayName("veza se trazi u zadanom stupcu, a ne bilo gdje u zapisu")
    void linkLooksAtTheGivenColumnOnly() {
        row("drugdje", TEMPLATE_ID, Map.of("nesto", 888));

        assertThat(queryRepository.countLinkedTo(TEMPLATE_ID, "proces", 888L)).isZero();
    }
}
