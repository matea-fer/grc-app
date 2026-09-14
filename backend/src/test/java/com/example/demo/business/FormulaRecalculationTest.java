package com.example.demo.business;

import com.example.demo.dto.CreateColumnDefinitionRequest;
import com.example.demo.dto.UpdateColumnDefinitionRequest;
import com.example.demo.model.ColumnEntry;
import com.example.demo.model.ColumnOptions;
import com.example.demo.model.SurveyResult;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - izmjena formule vrijedi i UNATRAG.
 *
 * Formula se dosad racunala samo pri spremanju zapisa, pa je njezina izmjena vrijedila tek
 * unaprijed: zapisi uneseni prije nje zadrzali su stari rezultat. To nije zastarjeli prikaz
 * nego neistina u podacima - stupac je tvrdio da je zbroj, a u njemu je stajao umnozak.
 *
 * Nadeno njezinom rucnom provjerom, na tocno tom primjeru: A i B, C kao umnozak, pa formula
 * promijenjena u zbroj.
 */
@Tag("business")
class FormulaRecalculationTest {

    private static final long TEMPLATE_ID = 10L;

    private final TemplateService templateService = mock(TemplateService.class);
    private final SurveyResultRepository surveyRepository = mock(SurveyResultRepository.class);
    private final RecordChangeService recordChangeService = mock(RecordChangeService.class);

    private final ColumnDefinitionService service = new ColumnDefinitionService(
            templateService, surveyRepository, mock(CodebookService.class),
            new SchemaValidator(mock(CodebookItemRepository.class), surveyRepository),
            new FormulaEvaluator(), mock(AttachmentCleanup.class), mock(LogService.class),
            recordChangeService);

    private Template template;
    private SurveyResult record;

    private static ColumnOptions formula(String expression) {
        return new ColumnOptions(false, null, null, null, null, null, null, null, false, false,
                expression, null, null);
    }

    private static ColumnEntry column(String key, String type, ColumnOptions options) {
        return new ColumnEntry(key, type, null, null, false, false, null,
                "formula".equals(type), null, options);
    }

    /** Obrazac s dva broja i formulom nad njima, te jednim vec spremljenim zapisom. */
    @BeforeEach
    void seed() {
        template = new Template(3L, "Izracun");
        template.setId(TEMPLATE_ID);
        template.setDefinitions(new ArrayList<>(List.of(
                column("a", "number", ColumnOptions.EMPTY),
                column("b", "number", ColumnOptions.EMPTY),
                column("c", "formula", formula("[a] * [b]")))));
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template);

        record = new SurveyResult();
        record.setId(500L);
        record.setCompanyId(3L);
        record.setTemplateId(TEMPLATE_ID);
        record.setData(new HashMap<>(Map.of(
                "a", new BigDecimal("3"), "b", new BigDecimal("10"), "c", new BigDecimal("30"))));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of(record));
    }

    private UpdateColumnDefinitionRequest asFormula(String key, String expression) {
        return new UpdateColumnDefinitionRequest(key, "formula", null, null, false, false, null,
                true, null, formula(expression), false);
    }

    private Object valueOf(String key) {
        return record.getData().get(key);
    }

    @Test
    @DisplayName("izmjena formule preracuna vec spremljene zapise")
    void recomputesExistingRecords() {
        service.update(TEMPLATE_ID, "c", asFormula("c", "[a] + [b]"));

        // 3 * 10 = 30 prije izmjene, 3 + 10 = 13 poslije
        assertThat(valueOf("c")).isEqualTo(new BigDecimal("13"));
    }

    /**
     * Formule se smiju pozivati jedna na drugu, pa promjena jedne mijenja i sve koje o njoj
     * ovise. Zato se racunaju SVE formule obrasca, a ne samo izmijenjena.
     */
    @Test
    @DisplayName("mijenja se i formula koja ovisi o izmijenjenoj")
    void recomputesDependentFormulas() {
        template.getDefinitions().add(column("d", "formula", formula("[c] + 1")));
        record.getData().put("d", new BigDecimal("31"));

        service.update(TEMPLATE_ID, "c", asFormula("c", "[a] + [b]"));

        assertThat(valueOf("c")).isEqualTo(new BigDecimal("13"));
        assertThat(valueOf("d")).isEqualTo(new BigDecimal("14"));
    }

    /** Netko ce se prije ili kasnije pitati zasto je broj drugaciji nego jucer. */
    @Test
    @DisplayName("promjena vrijednosti ulazi u povijest zapisa")
    void writesToRecordHistory() {
        service.update(TEMPLATE_ID, "c", asFormula("c", "[a] + [b]"));

        verify(recordChangeService).capture(eq(500L), eq(TEMPLATE_ID), eq(3L), any(), any());
    }

    /**
     * Nov stupac s formulom zatice zapise koji su nastali prije njega; bez preracuna bi u njima
     * ostao prazan sve dok se svaki ne otvori i spremi rukom - a formula je upravo ono sto
     * nitko ne unosi rucno.
     */
    @Test
    @DisplayName("nov stupac s formulom popuni zatecene zapise")
    void fillsExistingRecordsForNewColumn() {
        service.create(TEMPLATE_ID, new CreateColumnDefinitionRequest("zbroj", "formula", null, null,
                false, false, null, true, null, formula("[a] + [b]")));

        assertThat(valueOf("zbroj")).isEqualTo(new BigDecimal("13"));
    }

    /**
     * Stupac koji prestane biti formula ZADRZAVA zadnju izracunatu vrijednost - ona je obican
     * broj i od sada se unosi rukom. Brisanje bi bilo gubitak podatka, a to je odluka koja se
     * u ovoj aplikaciji uvijek trazi izricito.
     *
     * Vazno je ono drugo: formule koje se na njega pozivaju moraju se preracunati, jer im se
     * ulaz od sada vise ne mijenja sam.
     */
    @Test
    @DisplayName("stupac koji vise nije formula zadrzava vrijednost, a ovisne formule se preracunaju")
    void keepsValueWhenColumnStopsBeingFormula() {
        template.getDefinitions().add(column("d", "formula", formula("[c] + 1")));
        record.getData().put("d", new BigDecimal("31"));

        service.update(TEMPLATE_ID, "c", new UpdateColumnDefinitionRequest("c", "number", null, null,
                false, false, null, false, null, ColumnOptions.EMPTY, true));

        assertThat(valueOf("c")).isEqualTo(new BigDecimal("30"));
        assertThat(valueOf("d")).isEqualTo(new BigDecimal("31"));
    }

    /**
     * Izmjena koja formulu ne dira (ovdje: samo natpis stupca) ne smije zapisima mijenjati
     * vrijednosti ni upisivati im povijest - inace bi svaka sitna izmjena sheme izgledala kao
     * da je netko dirao podatke.
     */
    @Test
    @DisplayName("izmjena obicnog stupca ne preracunava nista")
    void leavesRecordsAloneForUnrelatedChange() {
        service.update(TEMPLATE_ID, "b", new UpdateColumnDefinitionRequest("b", "number", null,
                "Količina", false, false, null, false, null, ColumnOptions.EMPTY, false));

        assertThat(valueOf("c")).isEqualTo(new BigDecimal("30"));
        verify(recordChangeService, never()).capture(any(), any(), any(), any(), any());
    }

    // --- preimenovanje stupca na koji se formula poziva ---

    private String formulaOf(String key) {
        return template.getDefinitions().stream()
                .filter(entry -> entry.key().equals(key))
                .findFirst().orElseThrow().options().formula();
    }

    private UpdateColumnDefinitionRequest renameNumber(String newKey) {
        return new UpdateColumnDefinitionRequest(newKey, "number", null, null, false, false, null,
                false, null, ColumnOptions.EMPTY, false);
    }

    /**
     * Bez prepisivanja se formula lomi TIHO: i dalje trazi kljuc kojeg vise nema, pa se stupac
     * isprazni tek pri sljedecem spremanju zapisa - danima poslije, kad nitko vise ne povezuje
     * jedno s drugim. Preimenovanje ionako migrira kljuceve u svim zapisima; formula je isti
     * takav kljuc, samo zapisan u shemi.
     */
    @Test
    @DisplayName("preimenovan stupac se prepise u formuli koja ga koristi")
    void rewritesFormulaOnRename() {
        service.update(TEMPLATE_ID, "a", renameNumber("kolicina"));

        assertThat(formulaOf("c")).isEqualTo("[kolicina] * [b]");
        // vrijednost se nije promijenila - isti racun, drugi naziv
        assertThat(valueOf("c")).isEqualTo(new BigDecimal("30"));
    }

    /** Prepisuju se CIJELI pozivi, ne dijelovi teksta: {@code [a]} da, {@code [ab]} ne. */
    @Test
    @DisplayName("stupac slicnog naziva ostaje netaknut")
    void leavesSimilarKeysAlone() {
        template.getDefinitions().add(column("ab", "number", ColumnOptions.EMPTY));
        template.getDefinitions().add(column("e", "formula", formula("[ab] + [a]")));

        service.update(TEMPLATE_ID, "a", renameNumber("kolicina"));

        assertThat(formulaOf("e")).isEqualTo("[ab] + [kolicina]");
    }

    @Test
    @DisplayName("preimenovanje formule povlaci formulu koja se na nju poziva")
    void rewritesDependentFormulaOnRename() {
        template.getDefinitions().add(column("d", "formula", formula("[c] + 1")));

        service.update(TEMPLATE_ID, "c", new UpdateColumnDefinitionRequest("rezultat", "formula",
                null, null, false, false, null, true, null, formula("[a] * [b]"), false));

        assertThat(formulaOf("d")).isEqualTo("[rezultat] + 1");
    }

    @Test
    @DisplayName("preimenovanje stupca koji nijedna formula ne koristi ne dira formule")
    void leavesUnrelatedFormulasAlone() {
        template.getDefinitions().add(column("napomena", "string", ColumnOptions.EMPTY));

        service.update(TEMPLATE_ID, "napomena", new UpdateColumnDefinitionRequest("biljeska",
                "string", null, null, false, false, null, false, null, ColumnOptions.EMPTY, false));

        assertThat(formulaOf("c")).isEqualTo("[a] * [b]");
    }
}
