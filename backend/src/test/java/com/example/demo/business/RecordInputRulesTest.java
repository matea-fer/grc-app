package com.example.demo.business;

import com.example.demo.auth.AuthContext;
import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.dto.CreateSurveyRequest;
import com.example.demo.dto.UpdateSurveyRequest;
import com.example.demo.exception.SchemaValidationException;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - pravila unosa koja stupac nosi uz tip: obavezno, jedinstveno i
 * samo za citanje. Za razliku od pravila SAME DEFINICIJE ({@link ColumnAttributesTest}),
 * ovdje je rijec o spremanju pojedinog zapisa.
 *
 * Kljucno je da jedinstvenost NIJE provjera jedne vrijednosti: servis mora dohvatiti
 * ostale retke istog obrasca, a redak koji se upravo mijenja iz te usporedbe izostaviti -
 * inace bi izmjena koja jedinstveni stupac ni ne dira pala na vlastitoj staroj vrijednosti.
 *
 * ReadOnly se ne odbija greskom nego se namece: forma takva polja uopce ne nudi na unos,
 * pa je sadrzaj koji klijent posalje njegova stvar, a ne nesto sto korisnik treba ispravljati.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class RecordInputRulesTest {

    private static final long TEMPLATE_ID = 10L;

    @Mock
    private SurveyResultRepository repository;

    @Mock
    private ColumnDefinitionService columnDefinitionService;

    @Mock
    private TemplateService templateService;

    @Mock
    private LogService logService;

    @Mock
    private CodebookItemRepository codebookItems;

    @Mock
    private ColumnSequenceService columnSequenceService;

    @Mock
    private CodebookItemService codebookItemService;

    @Mock
    private AttachmentCleanup attachmentCleanup;

    @Mock
    private RecordChangeService recordChangeService;

    @Mock
    private ReferenceLookup referenceLookup;

    @Mock
    private SurveyQueryRepository queryRepository;

    private SurveyResultService service() {
        // Pravi validator - pravila koja se ovdje testiraju su bas njegova. Gradi se ovdje,
        // a ne u polju: @Mock polja Mockito postavlja tek nakon konstrukcije razreda.
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
    }

    /**
     * Baza javlja da NEKI DRUGI redak vec ima tu vrijednost.
     *
     * Od 12.08. jedinstvenost vise ne cita ostale retke u memoriji nego pita bazu jednim
     * upitom, pa se ovdje mocka bas to pitanje. {@code excludeId} je redak koji se sprema
     * (null kod novog) - i on je ovdje najvazniji dio: bez njega bi izmjena pala na
     * vlastitoj staroj vrijednosti.
     */
    private void valueTaken(Long excludeId, String columnKey, String value) {
        when(queryRepository.existsWithText(TEMPLATE_ID, excludeId, columnKey, value)).thenReturn(true);
    }

    /** Kojim je pitanjima o jedinstvenosti servis zvao bazu. */
    private void verifyAsked(Long excludeId, String columnKey, String value) {
        verify(queryRepository).existsWithText(TEMPLATE_ID, excludeId, columnKey, value);
    }

    private SurveyResult survey(Long id, Map<String, Object> data) {
        SurveyResult survey = new SurveyResult();
        survey.setId(id);
        survey.setCompanyId(3L);
        survey.setTemplateId(TEMPLATE_ID);
        survey.setData(new HashMap<>(data));
        return survey;
    }

    private void saveReturnsArgument() {
        when(repository.save(any(SurveyResult.class))).thenAnswer(invocation -> {
            SurveyResult toSave = invocation.getArgument(0);
            if (toSave.getId() == null) {
                toSave.setId(99L);
            }
            return toSave;
        });
    }

    private Map<String, Object> savedData() {
        ArgumentCaptor<SurveyResult> captor = ArgumentCaptor.forClass(SurveyResult.class);
        verify(repository).save(captor.capture());
        return captor.getValue().getData();
    }

    private ColumnDefinitionResponse column(String key, String type) {
        return new ColumnDefinitionResponse(key, type, null);
    }

    private ColumnDefinitionResponse required(String key) {
        return new ColumnDefinitionResponse(key, "string", null, null, true, false, null, false);
    }

    private ColumnDefinitionResponse unique(String key) {
        return new ColumnDefinitionResponse(key, "string", null, null, false, true, null, false);
    }

    private ColumnDefinitionResponse readOnly(String key, String defaultValue) {
        return new ColumnDefinitionResponse(key, "string", null, null, false, false, defaultValue, true);
    }

    // ===================== OBAVEZNO =====================

    @Test
    @DisplayName("zapis bez obaveznog polja se ne sprema")
    void requiredFieldBlocksSave() {
        templateOwned();
        schema(required("grad"));

        assertThatThrownBy(() -> service().create(TEMPLATE_ID, new CreateSurveyRequest(Map.of())))
                .isInstanceOf(SchemaValidationException.class);

        verify(repository, never()).save(any());
    }

    // ===================== JEDINSTVENO =====================

    /**
     * Jedinstvenost se ne da provjeriti iz jednog retka. Prije se zbog toga pri svakom
     * spremanju ucitavao CIJELI obrazac u memoriju; sada se pita baza, koja stane kod prvog
     * pogotka. Vazno je da se pita bas ono sto treba - zato se provjerava i sam upit.
     */
    @Test
    @DisplayName("novi zapis se odbija kad postojeci redak vec ima tu vrijednost")
    void uniqueIsCheckedAgainstExistingRows() {
        templateOwned();
        schema(unique("oib"));
        valueTaken(null, "oib", "12345");

        assertThatThrownBy(() -> service().create(TEMPLATE_ID, new CreateSurveyRequest(Map.of("oib", "12345"))))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("oib");

        verify(repository, never()).save(any());
        // kod novog zapisa nema sto izostaviti iz usporedbe
        verifyAsked(null, "oib", "12345");
    }

    @Test
    @DisplayName("novi zapis prolazi kad se vrijednost razlikuje od postojecih")
    void uniqueAcceptsFreshValue() {
        templateOwned();
        schema(unique("oib"));
        saveReturnsArgument();

        assertThatCode(() -> service().create(TEMPLATE_ID, new CreateSurveyRequest(Map.of("oib", "99999"))))
                .doesNotThrowAnyException();

        verifyAsked(null, "oib", "99999");
    }

    /**
     * Bez izostavljanja vlastitog retka nijedna izmjena postojeceg zapisa ne bi prosla:
     * njegova vlastita, nepromijenjena vrijednost bila bi mu duplikat. Sada to izostavljanje
     * radi sam upit ({@code id <> :excludeId}), pa se ovdje provjerava da mu je id predan.
     */
    @Test
    @DisplayName("izmjena zapisa ne pada na vlastitoj postojecoj vrijednosti")
    void updateDoesNotClashWithItself() {
        templateOwned();
        schema(unique("oib"));
        when(repository.findById(1L)).thenReturn(Optional.of(survey(1L, Map.of("oib", "12345"))));
        saveReturnsArgument();

        assertThatCode(() -> service().update(TEMPLATE_ID, 1L, new UpdateSurveyRequest(Map.of("oib", "12345"))))
                .doesNotThrowAnyException();

        verifyAsked(1L, "oib", "12345");
    }

    @Test
    @DisplayName("izmjena i dalje pada na vrijednosti koju ima drugi zapis")
    void updateStillClashesWithAnotherRow() {
        templateOwned();
        schema(unique("oib"));
        when(repository.findById(1L)).thenReturn(Optional.of(survey(1L, Map.of("oib", "12345"))));
        valueTaken(1L, "oib", "99999");

        assertThatThrownBy(() -> service().update(TEMPLATE_ID, 1L, new UpdateSurveyRequest(Map.of("oib", "99999"))))
                .isInstanceOf(SchemaValidationException.class);

        verify(repository, never()).save(any());
    }

    /**
     * Broj se ne smije pitati kao tekst: u jsonb-u "1" i "1.0" nisu isti niz znakova, a ista
     * su vrijednost - i pravilo jedinstvenosti oduvijek kaze da jesu ({@code SchemaValidator.sameValue}).
     */
    @Test
    @DisplayName("brojcani stupac se pita upitom za brojeve, ne za tekst")
    void numericUniqueUsesNumericQuery() {
        templateOwned();
        schema(new ColumnDefinitionResponse("sifra", "number", null, null, false, true, null, false));
        saveReturnsArgument();

        service().create(TEMPLATE_ID, new CreateSurveyRequest(Map.of("sifra", 12)));

        verify(queryRepository).existsWithNumber(TEMPLATE_ID, null, "sifra", new BigDecimal("12"));
        verify(queryRepository, never()).existsWithText(any(), any(), any(), any());
    }

    /**
     * Prazno polje smije se ponavljati koliko god puta - i to se vidi po tome sto se baza o
     * njemu uopce ne pita. Da se pita, upit bi trazio retke s praznom vrijednoscu i (na velikoj
     * tablici) radio posao za odgovor koji je unaprijed poznat.
     */
    @Test
    @DisplayName("prazna vrijednost se ne provjerava na jedinstvenost - baza se i ne pita")
    void emptyValueIsNotCheckedForUniqueness() {
        templateOwned();
        schema(unique("oib"));
        saveReturnsArgument();

        service().create(TEMPLATE_ID, new CreateSurveyRequest(new HashMap<>(Map.of("oib", "   "))));

        verify(queryRepository, never()).existsWithText(any(), any(), any(), any());
    }

    // ===================== SAMO ZA ČITANJE =====================

    @Test
    @DisplayName("novi zapis dobiva zadanu vrijednost readOnly stupca, bez obzira na poslano")
    void readOnlyTakesDefaultOnCreate() {
        templateOwned();
        schema(readOnly("izvor", "web"), column("grad", "string"));
        saveReturnsArgument();

        service().create(TEMPLATE_ID, new CreateSurveyRequest(new HashMap<>(Map.of(
                "izvor", "podmetnuto", "grad", "Split"))));

        assertThat(savedData()).containsEntry("izvor", "web").containsEntry("grad", "Split");
    }

    @Test
    @DisplayName("izmjena zadržava spremljenu vrijednost readOnly stupca")
    void readOnlyKeepsStoredValueOnUpdate() {
        templateOwned();
        schema(readOnly("izvor", "web"), column("grad", "string"));
        SurveyResult existing = survey(1L, Map.of("izvor", "uvoz", "grad", "Split"));
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        saveReturnsArgument();

        service().update(TEMPLATE_ID, 1L, new UpdateSurveyRequest(new HashMap<>(Map.of(
                "izvor", "podmetnuto", "grad", "Zagreb"))));

        // spremljena vrijednost pobjeduje poslanu, a obican stupac se normalno mijenja
        assertThat(savedData()).containsEntry("izvor", "uvoz").containsEntry("grad", "Zagreb");
    }

    /** Stupac dodan naknadno: redak ga jos nema, pa ga popuni zadana vrijednost. */
    @Test
    @DisplayName("izmjena popunjava readOnly stupac zadanom vrijednošću kad ga zapis još nema")
    void readOnlyFallsBackToDefaultOnUpdate() {
        templateOwned();
        schema(readOnly("izvor", "web"), column("grad", "string"));
        SurveyResult existing = survey(1L, Map.of("grad", "Split"));
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        saveReturnsArgument();

        service().update(TEMPLATE_ID, 1L, new UpdateSurveyRequest(new HashMap<>(Map.of("grad", "Split"))));

        assertThat(savedData()).containsEntry("izvor", "web");
    }

    @Test
    @DisplayName("readOnly stupac bez zadane vrijednosti ostaje prazan i kad je poslan")
    void readOnlyWithoutDefaultStaysEmpty() {
        templateOwned();
        schema(readOnly("izvor", null), column("grad", "string"));
        saveReturnsArgument();

        service().create(TEMPLATE_ID, new CreateSurveyRequest(new HashMap<>(Map.of(
                "izvor", "podmetnuto", "grad", "Split"))));

        assertThat(savedData()).doesNotContainKey("izvor");
    }
}
