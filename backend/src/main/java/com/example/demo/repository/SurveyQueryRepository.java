package com.example.demo.repository;

import com.example.demo.model.SurveyResult;
import com.example.demo.service.SurveyFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Upiti nad zapisima koje {@link SurveyResultRepository} ne moze izraziti: pretraga,
 * sortiranje i stranicenje po stupcima koji NISU stupci tablice nego kljucevi u jsonb
 * dokumentu.
 *
 * <b>Zasto rucni SQL.</b> Spring Data izvodi upit iz imena metode, a ime metode ne moze
 * govoriti o kljucu koji korisnik izmisli u Editoru. JPQL jsonb operatore ne poznaje. Ostaje
 * nativni SQL koji se sastavlja u letu.
 *
 * <b>Kljuc stupca ide kao PARAMETAR, ne kao dio teksta upita.</b> To je jedina obrana koja
 * stvarno drzi: naziv stupca dolazi iz zahtjeva, a lijepiti ga u tekst upita je put za
 * podmetanje SQL-a. Srecom, kod jsonb-a kljuc i jest vrijednost ({@code data ->> :kljuc}), pa
 * se parametar smije upotrijebiti i u {@code ORDER BY}. U tekst upita ulaze samo stvari koje
 * biramo MI iz zatvorenog popisa: smjer sortiranja i oblik usporedbe.
 *
 * <b>Svaki {@code ->} i {@code ->>} nosi izricit {@code cast(:kljuc as text)}.</b> Ta dva
 * operatora imaju po dvije inacice - za tekstualni kljuc i za redni broj u nizu - a parametar
 * bez tipa PostgreSQL ne zna razvrstati, pa upit padne s "operator is not unique". Bez casta
 * radi u testu s doslovnim nizom, a puca cim se vrijednost preda kao parametar.
 */
@Repository
public class SurveyQueryRepository {

    /**
     * Uzorak koji prepoznaje tekst koji se smije pretvoriti u broj.
     *
     * Postoji jer je {@code (data ->> 'cijena')::numeric} zamka: dovoljan je JEDAN redak u
     * kojem je pod tim kljucem ostalo nesto drugo (stupac je promijenio tip, a stariji zapis
     * zaostao) da cijeli upit padne - dakle da se ekran vise ne otvori zbog jednog retka.
     * Ovako neispravna vrijednost ispadne NULL i samo zavrsi na kraju poretka.
     */
    private static final String NUMERIC_TEXT = "'^-?[0-9]+(\\.[0-9]+)?$'";

    /** Broj iz jsonb-a, ili NULL kad ondje nije broj. Kljuc dolazi kao imenovani parametar. */
    private static final String AS_NUMBER = """
            CASE WHEN data ->> cast(:%1$s as text) ~ %2$s
                 THEN (data ->> cast(:%1$s as text))::numeric END""";

    /**
     * Tekst iz jsonb-a za POREDAK, gdje prazno vrijedi isto koliko i nepostojece.
     *
     * {@code nullif(btrim(...), '')} nije ukras. {@code NULLS LAST} pokriva samo redak koji
     * kljuc uopce nema; redak koji ga ima kao prazan tekst je za bazu obicna vrijednost, i to
     * najmanja - pa ispliva na VRH popisa. Zapis bez naziva je tako u dijalogu odabira stajao
     * ispred svih imenovanih (nadeno rucnom provjerom). Za citatelja su oba
     * slucaja isto: "ovdje nema nista", a to pripada na dno.
     */
    private static final String AS_LABEL = "nullif(btrim(lower(data ->> cast(:%1$s as text))), '')";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Jedna stranica zapisa jednog obrasca.
     *
     * @param sortKey     kljuc stupca po kojem se sortira, ili {@code null} za poredak unosa
     * @param sortNumeric sortira li se kao broj (inace kao tekst, bez obzira na velika slova)
     * @param descending  smjer sortiranja
     */
    public List<SurveyResult> find(Long templateId, List<SurveyFilter> filters,
                                   String sortKey, boolean sortNumeric, boolean descending,
                                   int page, int size) {
        return find(templateId, filters, null, sortKey, sortNumeric, descending, page, size);
    }

    /**
     * Ista stranica, ali suzena na zadane id-eve.
     *
     * Sluzi dijalogu povezanih zapisa u smjeru NAPRIJED: ondje se ne trazi "tko pokazuje na
     * mene" (sto je filtar) nego "evo tocno ovih zapisa, pokazi ih" - id-evi su vec u podacima
     * retka. Kroz obican filtar to ne bi islo: filtri govore o SADRZAJU jsonb-a, a id je stupac
     * tablice.
     *
     * @param ids id-evi na koje se dohvat suzava; {@code null} ili prazno znaci "bez suzenja"
     */
    public List<SurveyResult> find(Long templateId, List<SurveyFilter> filters, List<Long> ids,
                                   String sortKey, boolean sortNumeric, boolean descending,
                                   int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM survey_result WHERE template_id = :templateId");
        List<Object[]> parameters = appendFilters(sql, filters);
        appendIds(sql, ids);
        sql.append(orderBy(sortKey, sortNumeric, descending));

        Query query = entityManager.createNativeQuery(sql.toString(), SurveyResult.class);
        query.setParameter("templateId", templateId);
        bind(query, parameters);
        bindIds(query, ids);
        if (sortKey != null) {
            query.setParameter("sortKey", sortKey);
        }
        query.setFirstResult(page * size);
        query.setMaxResults(size);

        @SuppressWarnings("unchecked")
        List<SurveyResult> rows = query.getResultList();
        return rows;
    }

    /** Koliko zapisa odgovara istim uvjetima - bez njega se ne zna koliko stranica postoji. */
    public long count(Long templateId, List<SurveyFilter> filters) {
        return count(templateId, filters, null);
    }

    /** Isto brojanje, suzeno na zadane id-eve - vidi {@link #find}. */
    public long count(Long templateId, List<SurveyFilter> filters, List<Long> ids) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM survey_result WHERE template_id = :templateId");
        List<Object[]> parameters = appendFilters(sql, filters);
        appendIds(sql, ids);

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("templateId", templateId);
        bind(query, parameters);
        bindIds(query, ids);
        return ((Number) query.getSingleResult()).longValue();
    }

    private void appendIds(StringBuilder sql, List<Long> ids) {
        if (ids != null && !ids.isEmpty()) {
            sql.append(" AND id IN (:ids)");
        }
    }

    private void bindIds(Query query, List<Long> ids) {
        if (ids != null && !ids.isEmpty()) {
            query.setParameter("ids", ids);
        }
    }

    /**
     * Ima li vec neki DRUGI zapis istog obrasca ovu tekstualnu vrijednost u ovom stupcu.
     *
     * {@code lower(btrim(...))} nije ukras nego prijepis postojeceg pravila iz
     * {@code SchemaValidator.sameValue}: "Split " i "split" su ista vrijednost. Da se ovdje
     * usporeduje doslovno, jedinstvenost bi tiho promijenila znacenje - i to samo za nove
     * unose, dok bi stari zapisi ostali provjereni po starom pravilu.
     */
    public boolean existsWithText(Long templateId, Long excludeId, String columnKey, String value) {
        return exists(templateId, excludeId, columnKey,
                "lower(btrim(data ->> cast(:columnKey as text))) = lower(btrim(cast(:value as text)))",
                value);
    }

    /** Isto, ali po vrijednosti broja - {@code 1} i {@code 1.0} su isti broj. */
    public boolean existsWithNumber(Long templateId, Long excludeId, String columnKey, BigDecimal value) {
        return exists(templateId, excludeId, columnKey,
                "(" + AS_NUMBER.formatted("columnKey", NUMERIC_TEXT) + ") = cast(:value as numeric)",
                value);
    }

    private boolean exists(Long templateId, Long excludeId, String columnKey, String condition, Object value) {
        // Redak koji se upravo sprema mora ispasti iz usporedbe, inace bi izmjena koja
        // jedinstveni stupac ni ne dira pala na vlastitoj staroj vrijednosti.
        Query query = entityManager.createNativeQuery("""
                SELECT EXISTS (SELECT 1 FROM survey_result
                               WHERE template_id = :templateId
                                 AND (cast(:excludeId as bigint) IS NULL OR id <> cast(:excludeId as bigint))
                                 AND %s)""".formatted(condition));
        query.setParameter("templateId", templateId);
        query.setParameter("excludeId", excludeId);
        query.setParameter("columnKey", columnKey);
        query.setParameter("value", value);
        return Boolean.TRUE.equals(query.getSingleResult());
    }

    /**
     * Uvjeti pretrage, spojeni s {@code AND} - vise filtara suzava rezultat, ne siri ga.
     *
     * @return parove (naziv parametra, vrijednost) koje pozivatelj mora vezati uz upit
     */
    private List<Object[]> appendFilters(StringBuilder sql, List<SurveyFilter> filters) {
        List<Object[]> parameters = new ArrayList<>();
        for (int i = 0; i < filters.size(); i++) {
            SurveyFilter filter = filters.get(i);
            String keyParam = "k" + i;
            String valueParam = "v" + i;
            parameters.add(new Object[]{keyParam, filter.columnKey()});

            switch (filter.kind()) {
                case TEXT -> {
                    // ILIKE bi bilo krace, ali lower(...) LIKE lower(...) je isto pravilo koje
                    // vec vrijedi za jedinstvenost - bolje jedno pravilo na oba mjesta
                    sql.append(" AND lower(data ->> cast(:").append(keyParam)
                            .append(" as text)) LIKE lower(cast(:").append(valueParam).append(" as text))");
                    parameters.add(new Object[]{valueParam, "%" + filter.text() + "%"});
                }
                case NUMBER -> {
                    sql.append(" AND (").append(AS_NUMBER.formatted(keyParam, NUMERIC_TEXT))
                            .append(") = cast(:").append(valueParam).append(" as numeric)");
                    parameters.add(new Object[]{valueParam, filter.number()});
                }
                case CODES -> appendCodes(sql, parameters, filter, keyParam, valueParam);
                case REFERENCE -> {
                    sql.append(" AND ").append(linksTo(keyParam, valueParam));
                    parameters.add(new Object[]{valueParam, filter.recordId()});
                }
            }
        }
        return parameters;
    }

    /**
     * Uvjet "stupac {@code :kljuc} pokazuje na zapis {@code :id}".
     *
     * Dva clana, jer veza u jsonb-u moze stajati u dva oblika: kao broj ({@code "proces": 12})
     * kod stupca s jednim odabirom i kao niz ({@code "kontrole": [12, 15]}) kod visestrukog.
     * {@code @>} ("sadrzi") ne pokriva oba jednim izrazom - {@code {"k":12}} i {@code {"k":[12]}}
     * su za njega razliciti oblici.
     *
     * Zasto onda ipak {@code @>} nad CIJELIM {@code data}, a ne {@code data -> kljuc @> ...}
     * (sto bi bio jedan clan): samo ovaj oblik moze koristiti GIN indeks nad {@code data}
     * (V7). Uz {@code data -> kljuc} bi svaki upit citao sve zapise obrasca - a ovaj se uvjet
     * postavlja pri SVAKOM brisanju zapisa, nad svakim obrascem koji na njega moze pokazivati.
     *
     * Kljuc ide kroz {@code jsonb_build_object}, a ne lijepljenjem u tekst JSON-a: kljuc
     * izmislja korisnik u Editoru, pa bi navodnik u nazivu inace razbio dokument.
     */
    private String linksTo(String keyParam, String valueParam) {
        String object = "jsonb_build_object(cast(:%s as text), %s)";
        String id = "to_jsonb(cast(:" + valueParam + " as bigint))";
        return "(data @> " + object.formatted(keyParam, id)
                + " OR data @> " + object.formatted(keyParam, "jsonb_build_array(" + id + ")") + ")";
    }

    /**
     * Zapisi jednog obrasca koji preko zadanog stupca pokazuju na zadani zapis.
     *
     * Sluzi provjeri prije brisanja i prekidu veza. Ogranicen je brojem, jer se popis koristi
     * ili za poruku ("tko smeta", gdje je dvadesetak dovoljno) ili za izmjenu redom.
     */
    public List<SurveyResult> findLinkedTo(Long templateId, String columnKey, Long recordId, int limit) {
        Query query = entityManager.createNativeQuery(
                "SELECT * FROM survey_result WHERE template_id = :templateId AND "
                        + linksTo("columnKey", "recordId") + " ORDER BY id", SurveyResult.class);
        query.setParameter("templateId", templateId);
        query.setParameter("columnKey", columnKey);
        query.setParameter("recordId", recordId);
        query.setMaxResults(limit);

        @SuppressWarnings("unchecked")
        List<SurveyResult> rows = query.getResultList();
        return rows;
    }

    /**
     * Jedna stranica zapisa za dijalog odabira veze: trazi se po stupcu koji sluzi kao naziv
     * i po samom id-u.
     *
     * Zasto ILI, a ne I kao kod obicnih filtara: ovdje je jedno polje za upis, a korisnik u
     * njega upisuje ili dio naziva ili broj zapisa koji zna napamet. Da se uvjeti spajaju s I,
     * upis broja ne bi nasao nista.
     *
     * Poredak je po nazivu, a ne po id-u: dijalog se pregledava ocima, a ne redoslijedom unosa.
     * Zapisi bez naziva idu na DNO - vidi {@link #AS_LABEL}.
     */
    public List<SurveyResult> findOptions(Long templateId, String displayKey, String search,
                                          int page, int size) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM survey_result WHERE template_id = :templateId");
        appendSearch(sql, search);
        sql.append(" ORDER BY ").append(AS_LABEL.formatted("displayKey")).append(" NULLS LAST, id ASC");

        Query query = entityManager.createNativeQuery(sql.toString(), SurveyResult.class);
        // stupac za naziv je uvijek u upitu - stoji u ORDER BY, i kad se ne pretrazuje
        bindOptions(query, templateId, displayKey, search, true);
        query.setFirstResult(page * size);
        query.setMaxResults(size);

        @SuppressWarnings("unchecked")
        List<SurveyResult> rows = query.getResultList();
        return rows;
    }

    /** Koliko ih odgovara istoj pretrazi - dijalog ima straničenje kao i tablica. */
    public long countOptions(Long templateId, String displayKey, String search) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM survey_result WHERE template_id = :templateId");
        boolean searching = appendSearch(sql, search);

        Query query = entityManager.createNativeQuery(sql.toString());
        // Brojanje nema ORDER BY, pa se stupac za naziv u upitu pojavljuje SAMO kad se
        // pretrazuje. Vezati parametar kojeg u upitu nema nije bezazleno - JPA takav poziv
        // odbija, pa bi otvaranje dijaloga bez upisanog teksta padalo svaki put.
        bindOptions(query, templateId, displayKey, search, searching);
        return ((Number) query.getSingleResult()).longValue();
    }

    /** @return je li uvjet pretrage dodan (a time i parametri koje treba vezati) */
    private boolean appendSearch(StringBuilder sql, String search) {
        if (search == null || search.isBlank()) {
            return false;
        }
        // cast(id as text) = upisano, a ne LIKE: "1" ne smije naci zapise 1, 10, 100 i 1000
        sql.append(" AND (lower(data ->> cast(:displayKey as text)) LIKE lower(cast(:search as text))")
                .append(" OR cast(id as text) = cast(:exactId as text))");
        return true;
    }

    private void bindOptions(Query query, Long templateId, String displayKey, String search,
                             boolean usesDisplayKey) {
        query.setParameter("templateId", templateId);
        if (usesDisplayKey) {
            query.setParameter("displayKey", displayKey);
        }
        if (search != null && !search.isBlank()) {
            // Znakove za uzorak ovdje bjezi repozitorij, a ne servis kao kod ostalih filtara:
            // isti upisani tekst se koristi dvaput - jednom kao uzorak za LIKE, jednom kao
            // doslovan id. Da servis preda vec pobjegnuti tekst, usporedba id-a bi trazila
            // "100\%" umjesto "100%".
            query.setParameter("search", "%" + escapeForLike(search) + "%");
            query.setParameter("exactId", search.trim());
        }
    }

    /** Vidi {@code SurveyResultService.escapeForLike} - isto pravilo, ista svrha. */
    private String escapeForLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Koliko ih ukupno ima - poruka mora reci i koliko ih je preko prikazanih. */
    public long countLinkedTo(Long templateId, String columnKey, Long recordId) {
        Query query = entityManager.createNativeQuery(
                "SELECT count(*) FROM survey_result WHERE template_id = :templateId AND "
                        + linksTo("columnKey", "recordId"));
        query.setParameter("templateId", templateId);
        query.setParameter("columnKey", columnKey);
        query.setParameter("recordId", recordId);
        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * Stupac ima bilo koju od trazenih sifara.
     *
     * Operator {@code @>} ("sadrzi") radi za OBA oblika u kojima sifra moze stajati: za jednu
     * sifru ({@code "ZG"}) znaci jednakost, a za popis ({@code ["ZG","ST"]}) clanstvo. Zato
     * ovdje nema grananja po tome je li stupac visevrijednosni - a to grananje je upravo ono
     * sto bi se zaboravilo osvjeziti kad stupac promijeni postavku.
     *
     * Izbjegnut je i {@code ?|}, koji bi bio prirodniji zapis: znak {@code ?} JDBC cita kao
     * mjesto za parametar, pa upit pukne prije nego dode do baze.
     */
    private void appendCodes(StringBuilder sql, List<Object[]> parameters, SurveyFilter filter,
                             String keyParam, String valueParam) {
        sql.append(" AND (");
        for (int c = 0; c < filter.codes().size(); c++) {
            String codeParam = valueParam + "c" + c;
            if (c > 0) {
                sql.append(" OR ");
            }
            sql.append("data -> cast(:").append(keyParam).append(" as text) @> to_jsonb(cast(:")
                    .append(codeParam).append(" as text))");
            parameters.add(new Object[]{codeParam, filter.codes().get(c)});
        }
        sql.append(")");
    }

    /**
     * Poredak.
     *
     * {@code id} na kraju nije ukras: bez jednoznacnog poretka baza smije dva retka s istom
     * vrijednoscu vratiti bilo kojim redoslijedom, pa se isti zapis zna pojaviti na dvije
     * stranice ili nestati s obje. Uz stranicenje je to jedina razlika izmedu ispravnog i
     * naizgled ispravnog popisa.
     *
     * {@code NULLS LAST} jer prazno polje nije "najmanja vrijednost" nego "nema vrijednosti" -
     * i u oba smjera pripada na kraj.
     */
    private String orderBy(String sortKey, boolean numeric, boolean descending) {
        if (sortKey == null) {
            return " ORDER BY id " + (descending ? "DESC" : "ASC");
        }
        String expression = numeric
                ? "(" + AS_NUMBER.formatted("sortKey", NUMERIC_TEXT) + ")"
                : AS_LABEL.formatted("sortKey");
        return " ORDER BY " + expression + (descending ? " DESC" : " ASC") + " NULLS LAST, id ASC";
    }

    private void bind(Query query, List<Object[]> parameters) {
        for (Object[] parameter : parameters) {
            query.setParameter((String) parameter[0], parameter[1]);
        }
    }
}
