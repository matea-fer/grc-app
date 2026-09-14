package com.example.demo.business;

import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.exception.SchemaValidationException;
import com.example.demo.model.CodebookItem;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.service.SchemaValidator;
import com.example.demo.service.UniqueValueLookup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - shema je od sada UGOVOR, ne samo uputa za crtanje polja.
 *
 * Prije se u JSON moglo upisati bilo sto; sad ono sto nije u shemi firme ne
 * prolazi. Pravilo je nastalo kroz dogovor, nije generika - projekt bi jednako
 * dobro mogao odluciti da backend samo sprema kako jest.
 */
@Tag("business")
class SchemaValidationTest {

    private static final Long CODEBOOK_ID = 7L;

    private final CodebookItemRepository codebookItems = mock(CodebookItemRepository.class);
    private final SchemaValidator validator = new SchemaValidator(codebookItems, mock(SurveyResultRepository.class));

    private ColumnDefinitionResponse column(String key, String type) {
        return new ColumnDefinitionResponse(key, type, null);
    }

    /** Stupac koji vrijednosti uzima iz sifrarnika - dopusteni popis vise nije u shemi. */
    private ColumnDefinitionResponse fromCodebook(String key) {
        return new ColumnDefinitionResponse(key, "codebook", CODEBOOK_ID);
    }

    /** Sadrzaj sifrarnika na koji se stupac poziva; {@code active} je bitan samo za defaulte. */
    private void codebookHas(CodebookItem... items) {
        when(codebookItems.findByCodebookIdOrderBySortOrderAsc(CODEBOOK_ID)).thenReturn(List.of(items));
    }

    private CodebookItem item(String code, boolean active) {
        CodebookItem item = new CodebookItem();
        item.setCodebookId(CODEBOOK_ID);
        item.setCode(code);
        item.setName(code);
        item.setActive(active);
        return item;
    }

    private ColumnDefinitionResponse required(String key, String type) {
        return new ColumnDefinitionResponse(key, type, null, null, true, false, null, false);
    }

    private ColumnDefinitionResponse unique(String key, String type) {
        return new ColumnDefinitionResponse(key, type, null, null, false, true, null, false);
    }

    private void validate(Map<String, Object> data, ColumnDefinitionResponse... schema) {
        validator.validate(data, List.of(schema));
    }

    /**
     * Isto, ali s podacima ostalih redaka istog obrasca - podloga za jedinstvenost.
     *
     * Od 12.08. validator vise ne dobiva same retke nego PITANJE ({@link UniqueValueLookup}):
     * u pogonu na njega odgovara baza jednim upitom, a ovdje redci koje test navede. Pravilo
     * jednakosti ostaje ono isto ({@code SchemaValidator} ga i dalje primjenjuje).
     */
    @SafeVarargs
    private void validateAmong(Map<String, Object> data, List<ColumnDefinitionResponse> schema,
                               Map<String, Object>... otherRows) {
        validator.validate(data, schema, takenAmong(List.of(otherRows)));
    }

    /** Vrijednost je "zauzeta" ako je ima neki od navedenih redaka - po pravilu iz validatora. */
    private UniqueValueLookup takenAmong(List<Map<String, Object>> rows) {
        return (columnKey, value) -> rows.stream()
                .anyMatch(row -> validator.findDuplicate(java.util.Arrays.asList(value, row.get(columnKey))) != null);
    }

    @Test
    @DisplayName("podaci koji odgovaraju shemi prolaze")
    void validDataPasses() {
        codebookHas(item("HR", true));

        assertThatCode(() -> validate(
                Map.of("grad", "Split", "ocjena", 8, "drzava", "HR"),
                column("grad", "string"), column("ocjena", "number"), fromCodebook("drzava")
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("stupac kojeg nema u shemi se odbija")
    void unknownColumnIsRejected() {
        assertThatThrownBy(() -> validate(Map.of("tajniStupac", "x"), column("grad", "string")))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("tajniStupac");
    }

    @Test
    @DisplayName("kriv tip vrijednosti se odbija")
    void wrongTypeIsRejected() {
        assertThatThrownBy(() -> validate(Map.of("ocjena", "osam"), column("ocjena", "number")))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("ocjena");
    }

    @Test
    @DisplayName("sifrarnicki stupac prima samo sifre svog sifrarnika")
    void codebookColumnRejectsUnknownCode() {
        codebookHas(item("ST", true), item("ZG", true));

        assertThatCode(() -> validate(Map.of("grad", "ST"), fromCodebook("grad")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validate(Map.of("grad", "OS"), fromCodebook("grad")))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("OS");
    }

    /**
     * Iskljucivanje stavke znaci "ne nudi je vise za nove unose", a ne "ponisti sve sto
     * je vec upisano". Da se iskljucena sifra odbijala, jedno iskljucivanje bi zakljucalo
     * uredivanje svakog starog zapisa koji je nosi - i to zbog stupca s kojim korisnik
     * u tom trenutku nema nikakvog posla.
     */
    @Test
    @DisplayName("iskljucena stavka i dalje prolazi u vec upisanim zapisima")
    void inactiveCodeIsStillAccepted() {
        codebookHas(item("ST", true), item("ZG", false));

        assertThatCode(() -> validate(Map.of("grad", "ZG"), fromCodebook("grad")))
                .doesNotThrowAnyException();
    }

    /**
     * Stupac tipa "codebook" bez sifrarnika je pokvarena SHEMA, ne pokvaren podatak.
     * Blokirati upis znacilo bi kazniti korisnika za tudu gresku u definiciji stupca -
     * isto kao i kod tipa koji validator ne poznaje.
     */
    @Test
    @DisplayName("sifrarnicki stupac bez sifrarnika ne blokira upis")
    void codebookColumnWithoutCodebookDoesNotBlockWrites() {
        assertThatCode(() -> validate(Map.of("grad", "bilo sto"),
                new ColumnDefinitionResponse("grad", "codebook", null)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("date prima samo ISO oblik YYYY-MM-DD")
    void dateRejectsOtherFormats() {
        assertThatCode(() -> validate(Map.of("datum", "2026-07-22"), column("datum", "date")))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validate(Map.of("datum", "22.07.2026."), column("datum", "date")))
                .isInstanceOf(SchemaValidationException.class);
    }

    /**
     * Prazno polje nije greska - korisnik jednostavno nije popunio taj stupac.
     * Bez ovoga se ne bi mogao spremiti redak koji ne popunjava bas sve.
     */
    @Test
    @DisplayName("null vrijednost prolazi bez obzira na tip stupca")
    void nullValueIsAllowed() {
        Map<String, Object> data = new HashMap<>();
        data.put("ocjena", null);

        assertThatCode(() -> validate(data, column("ocjena", "number"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prazni podaci prolaze i kad shema ne postoji")
    void emptyDataPassesWithoutSchema() {
        assertThatCode(() -> validate(Map.of())).doesNotThrowAnyException();
    }

    // ===================== OBAVEZNO (required) =====================

    /**
     * Obavezan stupac se ne provjerava kroz poslana polja nego kroz shemu - inace
     * bi ga se zaobislo najlakse moguce: tako da se uopce ne posalje.
     */
    @Test
    @DisplayName("obavezan stupac koji uopce nije poslan se odbija")
    void requiredColumnMissingIsRejected() {
        assertThatThrownBy(() -> validate(Map.of("grad", "Split"),
                column("grad", "string"), required("ocjena", "number")))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("ocjena");
    }

    @Test
    @DisplayName("obavezan stupac poslan kao null ili prazan tekst se odbija")
    void requiredColumnEmptyIsRejected() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("grad", null);
        assertThatThrownBy(() -> validate(withNull, required("grad", "string")))
                .isInstanceOf(SchemaValidationException.class);

        assertThatThrownBy(() -> validate(Map.of("grad", "   "), required("grad", "string")))
                .isInstanceOf(SchemaValidationException.class);
    }

    // ===================== JEDINSTVENO (unique) =====================

    @Test
    @DisplayName("jedinstven stupac odbija vrijednost koju vec ima drugi redak")
    void uniqueColumnRejectsValueFromAnotherRow() {
        assertThatThrownBy(() -> validateAmong(Map.of("oib", "12345"), List.of(unique("oib", "string")),
                Map.of("oib", "99999"), Map.of("oib", "12345")))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("12345");
    }

    @Test
    @DisplayName("jedinstven stupac prolazi kad se vrijednost nigdje ne ponavlja")
    void uniqueColumnAcceptsFreshValue() {
        assertThatCode(() -> validateAmong(Map.of("oib", "12345"), List.of(unique("oib", "string")),
                Map.of("oib", "99999")))
                .doesNotThrowAnyException();
    }

    /**
     * "Split " i "split" su korisniku isti grad. Propustiti ih kao razlicite znacilo bi
     * da pravilo vrijedi samo dok svi tipkaju jednako.
     */
    @Test
    @DisplayName("jedinstvenost ne razlikuje velika slova ni rubne razmake")
    void uniqueColumnIgnoresCaseAndPadding() {
        assertThatThrownBy(() -> validateAmong(Map.of("grad", " split "), List.of(unique("grad", "string")),
                Map.of("grad", "Split")))
                .isInstanceOf(SchemaValidationException.class);
    }

    /** Isti broj iz JSON-a moze stici kao Integer ili Double - 5 i 5.0 su ista vrijednost. */
    @Test
    @DisplayName("jedinstvenost brojeva ide po vrijednosti, ne po zapisu")
    void uniqueColumnComparesNumbersByValue() {
        assertThatThrownBy(() -> validateAmong(Map.of("broj", 5), List.of(unique("broj", "number")),
                Map.of("broj", 5.0)))
                .isInstanceOf(SchemaValidationException.class);
    }

    /**
     * Jedinstvenost govori o upisanim vrijednostima. Da se odnosila i na prazno, samo
     * bi jedan redak smio ostaviti taj stupac nepopunjenim - sto nitko nije trazio.
     */
    @Test
    @DisplayName("prazna vrijednost ne pada na jedinstvenosti ma koliko redaka je prazno")
    void uniqueColumnIgnoresEmptyValues() {
        Map<String, Object> empty = new HashMap<>();
        empty.put("oib", null);

        assertThatCode(() -> validateAmong(empty, List.of(unique("oib", "string")),
                Map.of(), Map.of()))
                .doesNotThrowAnyException();
    }

    /**
     * Korisnik koji je u formi pogrijesio na tri mjesta treba to saznati odjednom,
     * a ne ispravljati jedno po jedno uz tri odbijena spremanja.
     */
    @Test
    @DisplayName("prijavljuju se SVE greske odjednom, ne samo prva")
    void allErrorsAreReportedAtOnce() {
        Map<String, Object> data = new HashMap<>();
        data.put("ocjena", "osam");
        data.put("nepoznato", "x");
        data.put("datum", "jucer");

        assertThatThrownBy(() -> validate(data,
                column("ocjena", "number"), column("datum", "date")))
                .isInstanceOfSatisfying(SchemaValidationException.class,
                        ex -> assertThat(ex.getErrors()).hasSize(3));
    }
}
