package com.example.demo.business;

import com.example.demo.dto.CreateColumnDefinitionRequest;
import com.example.demo.dto.UpdateColumnDefinitionRequest;
import com.example.demo.exception.InvalidColumnDefinitionException;
import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookItem;
import com.example.demo.model.CodebookScope;
import com.example.demo.model.ColumnEntry;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - stupac uz tip nosi i pravila unosa (label, obavezno, jedinstveno,
 * zadana vrijednost, samo za citanje).
 *
 * Ovdje se brane pravila SAME DEFINICIJE, ne pojedinog zapisa: neke kombinacije su
 * same sa sobom u sukobu i bolje ih je zaustaviti dok se shema deklarira nego dopustiti
 * stupac u koji se poslije nista ne moze upisati. Kako se pravila spremanja retka ponasaju
 * vidi {@link RecordInputRulesTest}.
 *
 * Validator je pravi (a ne mock) jer je bas on taj koji zna odgovara li zadana
 * vrijednost tipu stupca.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class ColumnAttributesTest {

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
        // Validator nije mock (bas on zna odgovara li zadana vrijednost tipu), pa ga ne moze
        // ubaciti @InjectMocks - sastavljamo rucno. Gradi se ovdje, a ne u polju: @Mock polja
        // Mockito postavlja tek nakon konstrukcije razreda, pa bi ih inicijalizator uhvatio kao null.
        return new ColumnDefinitionService(templateService, surveyRepository, codebookService,
                new SchemaValidator(codebookItems, surveyRepository), new FormulaEvaluator(), attachmentCleanup, logService, recordChangeService);
    }

    private Template template(ColumnEntry... entries) {
        Template template = new Template(3L, "Obrazac");
        template.setId(TEMPLATE_ID);
        template.setDefinitions(new ArrayList<>(List.of(entries)));
        return template;
    }

    private SurveyResult survey(Long id, Map<String, Object> data) {
        SurveyResult survey = new SurveyResult();
        survey.setId(id);
        survey.setCompanyId(3L);
        survey.setTemplateId(TEMPLATE_ID);
        survey.setData(new HashMap<>(data));
        return survey;
    }

    private CreateColumnDefinitionRequest newColumn(String key, String type, Long codebookId,
                                                    boolean required, boolean unique,
                                                    String defaultValue, boolean readOnly) {
        return new CreateColumnDefinitionRequest(key, type, codebookId, null, required, unique, defaultValue, readOnly);
    }

    /** Sifrarnik koji postoji, u dosegu firme, sa zadanim stavkama. */
    private void codebookHas(Long codebookId, String... codes) {
        when(codebookService.requireAccessible(codebookId)).thenReturn(new Codebook("Gradovi", CodebookScope.TENANT, 3L));
        List<CodebookItem> items = new ArrayList<>();
        for (String code : codes) {
            CodebookItem item = new CodebookItem();
            item.setCodebookId(codebookId);
            item.setCode(code);
            item.setName(code);
            item.setActive(true);
            items.add(item);
        }
        when(codebookItems.findByCodebookIdOrderBySortOrderAsc(codebookId)).thenReturn(items);
    }

    private UpdateColumnDefinitionRequest madeUnique(String key, String type) {
        return new UpdateColumnDefinitionRequest(key, type, null, null, false, true, null, false);
    }

    // ===================== ZADANA VRIJEDNOST =====================

    /**
     * Zadana vrijednost se unosi kao tekst za svaki tip stupca. Ako tekst tipu ne odgovara,
     * forma bi predpopunila upravo ono sto backend pri spremanju odbija - greska koju korisnik
     * nije napravio i ne bi znao ispraviti.
     */
    @Test
    @DisplayName("zadana vrijednost koja ne odgovara tipu se odbija")
    void defaultValueMustMatchType() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                newColumn("ocjena", "number", null, false, false, "puno", false)))
                .isInstanceOf(InvalidColumnDefinitionException.class);

        verify(templateService, never()).save(any());
    }

    /**
     * Zadana vrijednost sifrarnickog stupca je SIFRA, a ne naziv - u redak se sprema sifra.
     * Naziv bi predpopunio nesto sto backend pri spremanju odbija.
     */
    @Test
    @DisplayName("zadana vrijednost Å¡ifrarniÄkog stupca mora biti Å¡ifra iz tog Å¡ifrarnika")
    void defaultValueMustBeCodeFromCodebook() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());
        codebookHas(CODEBOOK_ID, "ST", "ZG");

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                newColumn("grad", "codebook", CODEBOOK_ID, false, false, "OS", false)))
                .isInstanceOf(InvalidColumnDefinitionException.class);

        assertThatCode(() -> service().create(TEMPLATE_ID,
                newColumn("grad", "codebook", CODEBOOK_ID, false, false, "ST", false)))
                .doesNotThrowAnyException();
    }

    /**
     * Tip i sifrarnik moraju ici zajedno. Sifrarnik na stupcu drugog tipa nitko ne bi citao -
     * ostao bi kao tiha neistina u shemi; stupac tipa "codebook" bez njega nema odakle uzeti
     * dopustene vrijednosti.
     */
    @Test
    @DisplayName("tip i Å¡ifrarnik ne mogu iÄ‡i jedno bez drugoga")
    void codebookAndTypeMustMatch() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                newColumn("grad", "codebook", null, false, false, null, false)))
                .isInstanceOf(InvalidColumnDefinitionException.class);

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                newColumn("grad", "string", CODEBOOK_ID, false, false, null, false)))
                .isInstanceOf(InvalidColumnDefinitionException.class);
    }

    @Test
    @DisplayName("zadana vrijednost koja odgovara tipu prolazi")
    void validDefaultValueIsAccepted() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatCode(() -> service().create(TEMPLATE_ID,
                newColumn("ocjena", "number", null, false, false, "5", false)))
                .doesNotThrowAnyException();
    }

    // ===================== KOMBINACIJE KOJE SE NE SLAÅ½U =====================

    /** Svaki novi redak bi krenuo od iste vrijednosti, pa bi drugi odmah bio duplikat. */
    @Test
    @DisplayName("jedinstven stupac ne moÅ¾e imati zadanu vrijednost")
    void uniqueWithDefaultValueIsRejected() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                newColumn("oib", "string", null, false, true, "00000000000", false)))
                .isInstanceOf(InvalidColumnDefinitionException.class);
    }

    /** Polje koje se mora popuniti, a ne moze se upisati, nije moguce zadovoljiti. */
    @Test
    @DisplayName("obavezan stupac koji se ne moÅ¾e ureÄ‘ivati mora imati zadanu vrijednost")
    void requiredReadOnlyWithoutDefaultIsRejected() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                newColumn("izvor", "string", null, true, false, null, true)))
                .isInstanceOf(InvalidColumnDefinitionException.class);
    }

    @Test
    @DisplayName("obavezan stupac koji se ne moÅ¾e ureÄ‘ivati prolazi sa zadanom vrijednoÅ¡Ä‡u")
    void requiredReadOnlyWithDefaultIsAccepted() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template());

        assertThatCode(() -> service().create(TEMPLATE_ID,
                newColumn("izvor", "string", null, true, false, "web", true)))
                .doesNotThrowAnyException();
    }

    // ===================== UKLJUÄŒIVANJE JEDINSTVENOSTI NAKNADNO =====================

    /**
     * Shema ne smije tvrditi nesto sto podaci demantiraju. Da se ukljucivanje propustilo,
     * korisnik bi to otkrio tek kad mu prvo spremanje nepromijenjenog retka padne.
     */
    @Test
    @DisplayName("jedinstvenost se ne moÅ¾e ukljuÄiti dok postojeÄ‡i zapisi imaju duplikat")
    void enablingUniqueIsRejectedWhenValuesAlreadyRepeat() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(new ColumnEntry("oib", "string", null)));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of(
                survey(1L, Map.of("oib", "12345")),
                survey(2L, Map.of("oib", "12345"))
        ));

        assertThatThrownBy(() -> service().update(TEMPLATE_ID, "oib", madeUnique("oib", "string")))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("12345");

        verify(templateService, never()).save(any());
    }

    @Test
    @DisplayName("jedinstvenost se moÅ¾e ukljuÄiti kad su postojeÄ‡e vrijednosti razliÄite")
    void enablingUniqueIsAcceptedWhenValuesDiffer() {
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(new ColumnEntry("oib", "string", null)));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of(
                survey(1L, Map.of("oib", "12345")),
                survey(2L, Map.of("oib", "99999"))
        ));

        assertThatCode(() -> service().update(TEMPLATE_ID, "oib", madeUnique("oib", "string")))
                .doesNotThrowAnyException();

        verify(templateService).save(any());
    }
}
