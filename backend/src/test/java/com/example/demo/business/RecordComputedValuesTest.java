package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.dto.CreateSurveyRequest;
import com.example.demo.dto.UpdateSurveyRequest;
import com.example.demo.model.CodebookItem;
import com.example.demo.model.ColumnOptions;
import com.example.demo.model.SurveyResult;
import com.example.demo.model.Template;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.SurveyQueryRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.service.ReferenceLookup;
import com.example.demo.service.AttachmentCleanup;
import com.example.demo.service.CodebookItemService;
import com.example.demo.service.ColumnDefinitionService;
import com.example.demo.service.ColumnSequenceService;
import com.example.demo.service.FormulaEvaluator;
import com.example.demo.service.LogService;
import com.example.demo.service.RecordChangeService;
import com.example.demo.service.SchemaValidator;
import com.example.demo.service.SurveyResultService;
import com.example.demo.service.TemplateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - vrijednosti koje pri spremanju zapisa postavlja SERVER, a ne korisnik:
 * automatski redni broj, rezultat formule i sifra nastala slobodnim unosom.
 *
 * Sve troje dijeli isto pravilo: klijent ih ne smije odrediti. Zato se ovdje ne provjerava
 * samo da vrijednost nastane, nego i da poslana vrijednost bude odbacena - inace bi zaobici
 * pravilo znacilo samo poslati drugaciji JSON.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class RecordComputedValuesTest {

    private static final long TEMPLATE_ID = 10L;
    private static final Long CODEBOOK_ID = 7L;

    @Mock
    private SurveyResultRepository repository;

    @Mock
    private ColumnDefinitionService columnDefinitionService;

    @Mock
    private TemplateService templateService;

    @Mock
    private ColumnSequenceService columnSequenceService;

    @Mock
    private CodebookItemService codebookItemService;

    @Mock
    private CodebookItemRepository codebookItems;

    @Mock
    private LogService logService;

    @Mock
    private AttachmentCleanup attachmentCleanup;

    @Mock
    private RecordChangeService recordChangeService;

    @Mock
    private ReferenceLookup referenceLookup;

    @Mock
    private SurveyQueryRepository queryRepository;

    private SurveyResultService service() {
        // Validator i racun formule su pravi razredi - ovdje se testira bas njihov ucinak
        // na spremljene podatke. Gradi se ovdje jer @Mock polja postoje tek nakon konstrukcije.
        return new SurveyResultService(repository, queryRepository, columnDefinitionService, new SchemaValidator(codebookItems, repository),
                templateService, columnSequenceService, codebookItemService, new FormulaEvaluator(), attachmentCleanup, recordChangeService, logService, new AuthContext(), referenceLookup);
    }

    private void templateOwned() {
        Template template = new Template(3L, "Obrazac");
        template.setId(TEMPLATE_ID);
        lenient().when(templateService.requireOwned(TEMPLATE_ID)).thenReturn(template);
    }

    private void schema(ColumnDefinitionResponse... columns) {
        when(columnDefinitionService.getForTemplate(TEMPLATE_ID)).thenReturn(List.of(columns));
        lenient().when(repository.save(any(SurveyResult.class))).thenAnswer(call -> call.getArgument(0));
    }

    private Map<String, Object> savedData() {
        ArgumentCaptor<SurveyResult> captor = ArgumentCaptor.forClass(SurveyResult.class);
        verify(repository).save(captor.capture());
        return captor.getValue().getData();
    }

    private static ColumnDefinitionResponse autoNumber(String key) {
        return new ColumnDefinitionResponse(key, "number", null, null, false, false, null, true, null,
                new ColumnOptions(true, "int", null, null, null, null, null, null, false, false, null, null, null));
    }

    private static ColumnDefinitionResponse formula(String key, String expression) {
        return new ColumnDefinitionResponse(key, "formula", null, null, false, false, null, true, null,
                new ColumnOptions(false, null, null, null, null, null, null, null, false, false, expression, null, null));
    }

    private static ColumnDefinitionResponse freeEntry(String key) {
        return new ColumnDefinitionResponse(key, "codebook", CODEBOOK_ID, null, false, false, null, false, null,
                new ColumnOptions(false, null, null, null, null, null, null, "dropdown", false, true, null, null, null));
    }

    /** Slobodan unos uz vise odabranih vrijednosti - u zapisu stoji popis sifara. */
    private static ColumnDefinitionResponse multiFreeEntry(String key) {
        return new ColumnDefinitionResponse(key, "codebook", CODEBOOK_ID, null, false, false, null, false, null,
                new ColumnOptions(false, null, null, null, null, null, null, "popup", true, true, null, null, null));
    }

    // ===================== AUTOMATSKI REDNI BROJ =====================

    @Test
    @DisplayName("nov zapis dobiva sljedeci redni broj od servera")
    void assignsNextSequenceNumber() {
        templateOwned();
        schema(autoNumber("rbr"), new ColumnDefinitionResponse("naziv", "string", null));
        when(columnSequenceService.next(TEMPLATE_ID, "rbr")).thenReturn(42L);

        service().create(TEMPLATE_ID, new CreateSurveyRequest(Map.of("naziv", "Prvi")));

        assertThat(savedData()).containsEntry("rbr", 42L);
    }

    @Test
    @DisplayName("redni broj poslan s klijenta se odbacuje")
    void ignoresClientSuppliedSequenceNumber() {
        templateOwned();
        schema(autoNumber("rbr"));
        when(columnSequenceService.next(TEMPLATE_ID, "rbr")).thenReturn(1L);

        // stupac je readOnly, pa poslana vrijednost pada jos prije brojaca; da nije tako,
        // dva bi zapisa mogla nositi isti "redni" broj
        service().create(TEMPLATE_ID, new CreateSurveyRequest(new HashMap<>(Map.of("rbr", 999))));

        assertThat(savedData()).containsEntry("rbr", 1L);
    }

    @Test
    @DisplayName("izmjena zapisa ne mijenja vec dodijeljen redni broj")
    void keepsSequenceNumberOnUpdate() {
        templateOwned();
        schema(autoNumber("rbr"), new ColumnDefinitionResponse("naziv", "string", null));
        SurveyResult stored = new SurveyResult();
        stored.setId(5L);
        stored.setTemplateId(TEMPLATE_ID);
        stored.setData(new HashMap<>(Map.of("rbr", 7L, "naziv", "Staro")));
        when(repository.findById(5L)).thenReturn(Optional.of(stored));

        service().update(TEMPLATE_ID, 5L, new UpdateSurveyRequest(Map.of("naziv", "Novo")));

        assertThat(savedData()).containsEntry("rbr", 7L);
        verify(columnSequenceService, never()).next(any(), any());
    }

    // ===================== FORMULA =====================

    @Test
    @DisplayName("rezultat formule se racuna i sprema pri svakom spremanju")
    void computesFormulaOnSave() {
        templateOwned();
        schema(new ColumnDefinitionResponse("cijena", "number", null),
                new ColumnDefinitionResponse("kolicina", "number", null),
                formula("ukupno", "[cijena] * [kolicina]"));

        service().create(TEMPLATE_ID, new CreateSurveyRequest(Map.of("cijena", 10, "kolicina", 3)));

        assertThat((BigDecimal) savedData().get("ukupno")).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("vrijednost formule poslana s klijenta se ne uvazava")
    void recomputesInsteadOfTrustingClient() {
        templateOwned();
        schema(new ColumnDefinitionResponse("cijena", "number", null), formula("ukupno", "[cijena] * 2"));

        service().create(TEMPLATE_ID, new CreateSurveyRequest(new HashMap<>(Map.of("cijena", 10, "ukupno", 999))));

        assertThat((BigDecimal) savedData().get("ukupno")).isEqualByComparingTo("20");
    }

    // ===================== SLOBODAN UNOS U SIFRARNIK =====================

    @Test
    @DisplayName("upisana vrijednost se pretvara u sifru iz sifrarnika")
    void turnsFreeTextIntoCode() {
        templateOwned();
        schema(freeEntry("dobavljac"));
        when(codebookItemService.ensureCode(CODEBOOK_ID, "Nova firma d.o.o.")).thenReturn("NOVA_FIRMA_D_O_O");
        // stavka je upravo nastala, pa je validator koji slijedi mora i vidjeti - u pravom
        // radu to osigurava flush u ensureCode
        CodebookItem created = new CodebookItem(CODEBOOK_ID, "NOVA_FIRMA_D_O_O", "Nova firma d.o.o.", true, 0);
        when(codebookItems.findByCodebookIdOrderBySortOrderAsc(CODEBOOK_ID)).thenReturn(List.of(created));

        // u zapis ide SIFRA, a ne ono sto je covjek upisao - naziv se smije mijenjati, sifra ne
        service().create(TEMPLATE_ID, new CreateSurveyRequest(Map.of("dobavljac", "Nova firma d.o.o.")));

        assertThat(savedData()).containsEntry("dobavljac", "NOVA_FIRMA_D_O_O");
    }

    /**
     * Visevrijednosni stupac nosi POPIS u kojem se postojece sifre i novoupisani nazivi mijesaju -
     * korisnik je dio vrijednosti odabrao, a dio upisao. Svaki clan prolazi isto pravilo.
     */
    @Test
    @DisplayName("u popisu se novoupisani nazivi pretvaraju u sifre, a odabrane sifre ostaju")
    void resolvesEachValueInList() {
        templateOwned();
        schema(multiFreeEntry("dobavljaci"));
        when(codebookItemService.ensureCode(CODEBOOK_ID, "ZG")).thenReturn("ZG");
        when(codebookItemService.ensureCode(CODEBOOK_ID, "Nova firma")).thenReturn("NOVA_FIRMA");
        when(codebookItems.findByCodebookIdOrderBySortOrderAsc(CODEBOOK_ID)).thenReturn(List.of(
                new CodebookItem(CODEBOOK_ID, "ZG", "Zagreb", true, 0),
                new CodebookItem(CODEBOOK_ID, "NOVA_FIRMA", "Nova firma", true, 1)));

        service().create(TEMPLATE_ID, new CreateSurveyRequest(Map.of("dobavljaci", List.of("ZG", "Nova firma"))));

        assertThat(savedData()).containsEntry("dobavljaci", List.of("ZG", "NOVA_FIRMA"));
    }

    @Test
    @DisplayName("ista vrijednost odabrana i upisana zavrsava u zapisu jednom")
    void dropsDuplicatesAfterResolving() {
        templateOwned();
        schema(multiFreeEntry("dobavljaci"));
        // "ZG" i "Zagreb" su ista stavka - jedno je sifra, drugo naziv
        when(codebookItemService.ensureCode(CODEBOOK_ID, "ZG")).thenReturn("ZG");
        when(codebookItemService.ensureCode(CODEBOOK_ID, "Zagreb")).thenReturn("ZG");
        when(codebookItems.findByCodebookIdOrderBySortOrderAsc(CODEBOOK_ID))
                .thenReturn(List.of(new CodebookItem(CODEBOOK_ID, "ZG", "Zagreb", true, 0)));

        service().create(TEMPLATE_ID, new CreateSurveyRequest(Map.of("dobavljaci", List.of("ZG", "Zagreb"))));

        assertThat(savedData()).containsEntry("dobavljaci", List.of("ZG"));
    }

    @Test
    @DisplayName("prazan slobodan unos ne dira sifrarnik")
    void emptyFreeEntryDoesNotTouchCodebook() {
        templateOwned();
        schema(freeEntry("dobavljac"));

        service().create(TEMPLATE_ID, new CreateSurveyRequest(new HashMap<>(Map.of("dobavljac", "   "))));

        verify(codebookItemService, never()).ensureCode(any(), any());
    }
}
