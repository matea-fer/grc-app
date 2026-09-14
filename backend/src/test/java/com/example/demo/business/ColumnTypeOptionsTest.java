package com.example.demo.business;

import com.example.demo.dto.CreateColumnDefinitionRequest;
import com.example.demo.exception.InvalidColumnDefinitionException;
import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookScope;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.ColumnOptions;
import com.example.demo.model.Template;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.service.AttachmentCleanup;
import com.example.demo.service.CodebookService;
import com.example.demo.service.ColumnDefinitionService;
import com.example.demo.service.FormulaEvaluator;
import com.example.demo.service.LogService;
import com.example.demo.service.RecordChangeService;
import com.example.demo.service.SchemaValidator;
import com.example.demo.service.TemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - postavke koje ovise o TIPU stupca (raspon broja, uzorak, nacin odabira
 * iz sifrarnika, formula, gumb).
 *
 * Zajednicka nit svih pravila: postavka koju nitko nece citati se odbija umjesto da se tiho
 * zanemari. Takva bi postavka u shemi bila tiha neistina - stupac bi tvrdio da ima provjeru
 * koja se nikad ne ukljucuje, i ozivjela bi tek kad se stupcu jednom promijeni tip.
 *
 * Sam racun formule je u FormulaEvaluatorTest; ovdje je tema samo koja je formula dopustena.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class ColumnTypeOptionsTest {

    private static final long TEMPLATE_ID = 10L;
    private static final Long CODEBOOK_ID = 7L;

    @Mock
    private TemplateService templateService;

    @Mock
    private SurveyResultRepository surveyRepository;

    @Mock
    private LogService logService;

    @Mock
    private CodebookService codebookService;

    @Mock
    private CodebookItemRepository codebookItems;

    @Mock
    private AttachmentCleanup attachmentCleanup;

    @Mock
    private RecordChangeService recordChangeService;

    private ColumnDefinitionService service() {
        // Validator i racun formule su pravi razredi - bas su oni ti koji znaju odgovara li
        // postavka stupcu. Gradi se ovdje jer @Mock polja postoje tek nakon konstrukcije.
        return new ColumnDefinitionService(templateService, surveyRepository, codebookService,
                new SchemaValidator(codebookItems, surveyRepository), new FormulaEvaluator(), attachmentCleanup, logService, recordChangeService);
    }

    private Template template(ColumnEntry... entries) {
        Template template = new Template(3L, "Obrazac");
        template.setId(TEMPLATE_ID);
        template.setDefinitions(new ArrayList<>(List.of(entries)));
        return template;
    }

    // Postavke u kojima je zadano samo ono sto test ispituje - ostalo je prazno.

    private static ColumnOptions number(boolean autoIncrement, String mode, String min, String max, String pattern) {
        return new ColumnOptions(autoIncrement, mode,
                min == null ? null : new BigDecimal(min),
                max == null ? null : new BigDecimal(max),
                null, pattern, null, null, false, false, null, null, null);
    }

    private static ColumnOptions codebook(String pickerMode, boolean multiple, boolean allowNewValues) {
        return new ColumnOptions(false, null, null, null, null, null, null,
                pickerMode, multiple, allowNewValues, null, null, null);
    }

    private static ColumnOptions formula(String expression) {
        return new ColumnOptions(false, null, null, null, null, null, null, null, false, false, expression, null, null);
    }

    private CreateColumnDefinitionRequest column(String key, String type, ColumnOptions options) {
        return new CreateColumnDefinitionRequest(key, type, null, null, false, false, null, false, null, options);
    }

    private ColumnEntry savedColumn() {
        ArgumentCaptor<Template> captor = ArgumentCaptor.forClass(Template.class);
        verify(templateService).save(captor.capture());
        List<ColumnEntry> definitions = captor.getValue().getDefinitions();
        return definitions.getLast();
    }

    // ===================== POSTAVKA MORA PRIPADATI TIPU =====================

    @Test
    @DisplayName("postavka koja se tipu ne tice se odbija umjesto da se zanemari")
    void rejectsOptionThatDoesNotBelongToType() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        // raspon na tekstu nitko ne bi citao - ostao bi u shemi kao tvrdnja koju nista ne provodi
        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                column("naziv", "string", number(false, null, "1", "10", null))))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("Tekst");

        verify(templateService, never()).save(any());
    }

    @Test
    @DisplayName("uzorak se smije postaviti i na tekst i na broj")
    void patternIsAllowedOnTextAndNumber() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatCode(() -> service().create(TEMPLATE_ID,
                column("posta", "string", number(false, null, null, null, "^\\d{5}$"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("neispravan regularni izraz se odbija pri definiranju stupca")
    void rejectsBrokenPattern() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        // pri upisu retka bi se takav uzorak tiho preskocio, pa bi stupac izgledao kao da
        // ima provjeru koja se nikad ne ukljucuje - gore od stupca koji je nema
        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                column("posta", "string", number(false, null, null, null, "^\\d{5"))))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("Uzorak");
    }

    // ===================== BROJ =====================

    @Test
    @DisplayName("najmanja vrijednost ne smije biti veca od najvece")
    void rejectsInvertedRange() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                column("ocjena", "number", number(false, null, "10", "1", null))))
                .isInstanceOf(InvalidColumnDefinitionException.class);
    }

    @Test
    @DisplayName("automatski redni broj ne moze imati zadanu vrijednost")
    void autoIncrementRejectsDefaultValue() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        // broj dodjeljuje server; zadana vrijednost bi tvrdila suprotno
        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                new CreateColumnDefinitionRequest("rbr", "number", null, null, false, false, "1", false,
                        null, number(true, "int", null, null, null))))
                .isInstanceOf(InvalidColumnDefinitionException.class);
    }

    @Test
    @DisplayName("automatski redni broj mora biti cijeli broj")
    void autoIncrementRequiresIntegerMode() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                column("rbr", "number", number(true, "float", null, null, null))))
                .isInstanceOf(InvalidColumnDefinitionException.class);
    }

    @Test
    @DisplayName("automatski redni broj postaje samo za citanje i kad to nije zatrazeno")
    void autoIncrementForcesReadOnly() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        // vrijednost racuna server, pa je polje za unos besmisleno; odbiti stupac bilo bi
        // tocno, ali bi znacilo greskom na nesto sto nema drugog ispravnog odgovora
        service().create(TEMPLATE_ID, column("rbr", "number", number(true, "int", null, null, null)));

        assertThat(savedColumn().readOnly()).isTrue();
    }

    // ===================== SIFRARNIK =====================

    @Test
    @DisplayName("visestruki odabir se ne prikazuje padajucim izbornikom")
    void rejectsMultipleWithDropdown() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());
        when(codebookService.requireAccessible(CODEBOOK_ID))
                .thenReturn(new Codebook("Gradovi", CodebookScope.TENANT, 3L));

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                new CreateColumnDefinitionRequest("gradovi", "codebook", CODEBOOK_ID, null, false, false, null, false,
                        null, codebook("dropdown", true, false))))
                .isInstanceOf(InvalidColumnDefinitionException.class);
    }

    @Test
    @DisplayName("stupac s visestrukim odabirom ne moze biti jedinstven")
    void rejectsMultipleWithUnique() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());
        when(codebookService.requireAccessible(CODEBOOK_ID))
                .thenReturn(new Codebook("Gradovi", CodebookScope.TENANT, 3L));

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                new CreateColumnDefinitionRequest("gradovi", "codebook", CODEBOOK_ID, null, false, true, null, false,
                        null, codebook("checkbox", true, false))))
                .isInstanceOf(InvalidColumnDefinitionException.class);
    }

    /**
     * Slobodan unos znaci da obican korisnik dopunjuje sifrarnik unosom zapisa. Nad globalnim
     * sifrarnikom to bi znacilo da unos jedne firme mijenja popis SVIM firmama - i to bez
     * ijedne administratorske ovlasti.
     */
    @Test
    @DisplayName("slobodan unos se ne moze ukljuciti nad globalnim sifrarnikom")
    void rejectsFreeEntryIntoGlobalCodebook() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());
        when(codebookService.requireAccessible(CODEBOOK_ID))
                .thenReturn(new Codebook("Države", CodebookScope.GLOBAL, null));

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                new CreateColumnDefinitionRequest("drzava", "codebook", CODEBOOK_ID, null, false, false, null, false,
                        null, codebook("dropdown", false, true))))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("globalnim");
    }

    /**
     * Visestruki odabir trazi IZRICIT nacin prikaza, ne oslanja se na zadani: stupac koji drzi
     * popis mora reci kako ga prikazuje, jer padajuci izbornik za to ne dolazi u obzir.
     */
    @Test
    @DisplayName("visestruki odabir bez odabranog nacina prikaza se odbija")
    void rejectsMultipleWithoutPickerMode() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());
        when(codebookService.requireAccessible(CODEBOOK_ID))
                .thenReturn(new Codebook("Gradovi", CodebookScope.TENANT, 3L));

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                new CreateColumnDefinitionRequest("gradovi", "codebook", CODEBOOK_ID, null, false, false, null, false,
                        null, codebook(null, true, false))))
                .isInstanceOf(InvalidColumnDefinitionException.class);
    }

    /** Slobodan unos je DODATAK nacinu prikaza, a ne zamjena - ide i uz vise odabranih vrijednosti. */
    @Test
    @DisplayName("slobodan unos ide zajedno s visestrukim odabirom")
    void allowsFreeEntryWithMultiple() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());
        when(codebookService.requireAccessible(CODEBOOK_ID))
                .thenReturn(new Codebook("Dobavljači", CodebookScope.TENANT, 3L));

        assertThatCode(() -> service().create(TEMPLATE_ID,
                new CreateColumnDefinitionRequest("dobavljaci", "codebook", CODEBOOK_ID, null, false, false, null, false,
                        null, codebook("popup", true, true))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("slobodan unos nad sifrarnikom firme prolazi")
    void allowsFreeEntryIntoTenantCodebook() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());
        when(codebookService.requireAccessible(CODEBOOK_ID))
                .thenReturn(new Codebook("Dobavljači", CodebookScope.TENANT, 3L));

        assertThatCode(() -> service().create(TEMPLATE_ID,
                new CreateColumnDefinitionRequest("dobavljac", "codebook", CODEBOOK_ID, null, false, false, null, false,
                        null, codebook("dropdown", false, true))))
                .doesNotThrowAnyException();
    }

    // ===================== FORMULA =====================

    @Test
    @DisplayName("formula se mora pozivati na stupac koji u obrascu postoji")
    void rejectsFormulaWithUnknownColumn() {
        when(templateService.requireEditable(TEMPLATE_ID))
                .thenReturn(template(new ColumnEntry("cijena", "number", null)));

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                column("ukupno", "formula", formula("[cijena] * [kolicina]"))))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("kolicina");
    }

    @Test
    @DisplayName("formula se ne moze pozivati na stupac koji nije broj")
    void rejectsFormulaOverNonNumericColumn() {
        when(templateService.requireEditable(TEMPLATE_ID))
                .thenReturn(template(new ColumnEntry("naziv", "string", null)));

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                column("ukupno", "formula", formula("[naziv] * 2"))))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("nije broj");
    }

    @Test
    @DisplayName("formula se ne moze pozivati sama na sebe")
    void rejectsSelfReference() {
        when(templateService.requireEditable(TEMPLATE_ID))
                .thenReturn(template(new ColumnEntry("cijena", "number", null)));

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                column("ukupno", "formula", formula("[ukupno] + [cijena]"))))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("sama na sebe");
    }

    /**
     * Krug se ne otkriva pri racunanju nego OVDJE: korisnik koji unosi zapis nije ni vidio
     * formulu, a ne bi je znao ni popraviti.
     */
    @Test
    @DisplayName("dvije formule koje se pozivaju jedna na drugu se odbijaju")
    void rejectsCycleBetweenFormulas() {
        ColumnEntry existing = new ColumnEntry("a", "formula", null, null, false, false, null, true,
                null, formula("[b] + 1"));
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(existing));

        assertThatThrownBy(() -> service().create(TEMPLATE_ID, column("b", "formula", formula("[a] + 1"))))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("u krug");
    }

    @Test
    @DisplayName("formula nad drugom formulom je dopustena dok nema kruga")
    void allowsFormulaOverFormula() {
        ColumnEntry neto = new ColumnEntry("neto", "formula", null, null, false, false, null, true,
                null, formula("[cijena] * [kolicina]"));
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(
                new ColumnEntry("cijena", "number", null),
                new ColumnEntry("kolicina", "number", null),
                neto));

        assertThatCode(() -> service().create(TEMPLATE_ID, column("ukupno", "formula", formula("[neto] * 1,25"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("formula postaje samo za citanje")
    void formulaForcesReadOnly() {
        when(templateService.requireEditable(TEMPLATE_ID))
                .thenReturn(template(new ColumnEntry("cijena", "number", null)));

        service().create(TEMPLATE_ID, column("ukupno", "formula", formula("[cijena] * 2")));

        assertThat(savedColumn().readOnly()).isTrue();
    }

    // ===================== GUMB I SIRINA =====================

    @Test
    @DisplayName("gumb ne sprema vrijednost, pa ne moze biti obavezan")
    void buttonCannotBeRequired() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                new CreateColumnDefinitionRequest("akcija", "button", null, null, true, false, null, false,
                        null, new ColumnOptions(false, null, null, null, null, null, null, null, false, false,
                        null, "Učitaj dokument", null))))
                .isInstanceOf(InvalidColumnDefinitionException.class);
    }

    @Test
    @DisplayName("gumb prima obje radnje - povijest i zakljucavanje zapisa")
    void buttonAcceptsBothActions() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatCode(() -> service().create(TEMPLATE_ID, buttonColumn("zakljucaj", ColumnOptions.ACTION_LOCK)))
                .doesNotThrowAnyException();
    }

    /**
     * Radnja je NAZIV, a ne zastavica, pa shema smije obecati samo ono sto sucelje stvarno
     * izvodi. Gumb s izmisljenom radnjom bi se nacrtao i ne bi radio nista - i to bez ijedne
     * poruke, jer bi shema tvrdila da je sve u redu.
     */
    @Test
    @DisplayName("nepoznata radnja gumba se odbija")
    void rejectsUnknownButtonAction() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatThrownBy(() -> service().create(TEMPLATE_ID, buttonColumn("posalji", "posalji-mail")))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("Nepoznata radnja");
    }

    /** Gumb s natpisom i zadanom radnjom - jedino sto tip "button" uopce nosi. */
    private static CreateColumnDefinitionRequest buttonColumn(String key, String action) {
        return new CreateColumnDefinitionRequest(key, "button", null, null, false, false, null, false,
                null, new ColumnOptions(false, null, null, null, null, null, null, null, false, false,
                null, "Zaključaj", action));
    }

    @Test
    @DisplayName("besmislena sirina stupca se odbija")
    void rejectsUnusableWidth() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                new CreateColumnDefinitionRequest("naziv", "string", null, null, false, false, null, false,
                        5, ColumnOptions.EMPTY)))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("Širina");
    }
}
