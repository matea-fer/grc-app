package com.example.demo.business;

import com.example.demo.dto.UpdateColumnDefinitionRequest;
import com.example.demo.exception.ColumnDataLossException;
import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookItem;
import com.example.demo.model.CodebookScope;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - domensko pravilo: uredivanje stupca migrira postojece zapise.
 *
 * Odluka korisnice: preimenovanje mijenja kljuc u svim zapisima templatea, a
 * promjena tipa/dopustenih vrijednosti cisti vrijednosti koje novom stupcu vise
 * ne odgovaraju (umjesto da ih ostavi kao "smece" ili srusi upis).
 *
 * Provjera po polju je prava (pravi {@link SchemaValidator}), jer je bas ona
 * pravilo koje se testira; sam SurveyResultRepository je mockiran.
 */
@Tag("business")
@ExtendWith(MockitoExtension.class)
class ColumnEditingTest {

    private static final long TEMPLATE_ID = 10L;

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

    private ColumnDefinitionService service;

    private Template template(ColumnEntry... entries) {
        Template template = new Template(3L, "Obrazac");
        template.setId(TEMPLATE_ID);
        template.setDefinitions(new ArrayList<>(List.of(entries)));
        return template;
    }

    /**
     * Stupac bez dodatnih atributa, uz POTVRDENI gubitak podataka.
     *
     * Ovdje se testira migracija zapisa, a ne pitanje koje joj prethodi - pa svaka izmjena
     * dolazi kao da je korisnik potvrdu vec dao. Samo pitanje (i sto se dogada bez njega)
     * testira se zasebno, nize.
     */
    private UpdateColumnDefinitionRequest changedTo(String key, String type, Long codebookId) {
        return new UpdateColumnDefinitionRequest(key, type, codebookId, null, false, false, null, false,
                null, ColumnOptions.EMPTY, true);
    }

    /** Ista izmjena, ali bez potvrde - onako kako je posalje prvi klik u Editoru. */
    private UpdateColumnDefinitionRequest changedToUnconfirmed(String key, String type, Long codebookId) {
        return new UpdateColumnDefinitionRequest(key, type, codebookId, null, false, false, null, false);
    }

    /** Sifrarnik koji postoji i sadrzi zadane sifre. */
    private void codebookHas(Long codebookId, String... codes) {
        when(codebookService.requireAccessible(codebookId))
                .thenReturn(new Codebook("Gradovi", CodebookScope.TENANT, 3L));
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

    private SurveyResult survey(Long id, Map<String, Object> data) {
        SurveyResult survey = new SurveyResult();
        survey.setId(id);
        survey.setCompanyId(3L);
        survey.setTemplateId(TEMPLATE_ID);
        survey.setData(new HashMap<>(data));
        return survey;
    }

    private List<SurveyResult> savedSurveys() {
        ArgumentCaptor<SurveyResult> captor = ArgumentCaptor.forClass(SurveyResult.class);
        verify(surveyRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private ColumnDefinitionService service() {
        // Pravi validator - provjera po polju je bas ono pravilo koje ovdje testiramo, pa ga
        // ne moze ubaciti @InjectMocks. Gradi se ovdje jer @Mock polja postoje tek nakon
        // konstrukcije razreda.
        return new ColumnDefinitionService(templateService, surveyRepository, codebookService,
                new SchemaValidator(codebookItems, surveyRepository), new FormulaEvaluator(), attachmentCleanup, logService, recordChangeService);
    }

    // ===================== POTVRDA GUBITKA PODATAKA =====================

    /**
     * Ovo je pravilo koje je 12.08.2026. nastalo iz stvarne stete: izmjena uzorka na stupcu
     * `oib` je u jednom kliku ostavila 120 zapisa bez vrijednosti, bez pitanja i bez traga.
     *
     * Zato izmjena koja zapisima UZIMA vrijednosti mora biti izricito potvrdena, a odbijanje
     * mora doci PRIJE spremanja: shema ne smije ostati promijenjena ako se korisnik predomisli.
     */
    @Test
    @DisplayName("izmjena koja zapisima uzima vrijednosti se bez potvrde odbija - i nista ne sprema")
    void unconfirmedDataLossIsRejected() {
        service = service();
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(
                new ColumnEntry("oib", "string", null)));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of(
                survey(1L, Map.of("oib", "00000000001")),
                survey(2L, Map.of("oib", "00000000002"))
        ));

        assertThatThrownBy(() -> service.update(TEMPLATE_ID, "oib",
                changedToUnconfirmed("oib", "number", null)))
                .isInstanceOf(ColumnDataLossException.class)
                .hasMessageContaining("2")
                .hasMessageContaining("oib");

        // ni shema ni zapisi ne smiju biti dirnuti
        verify(templateService, never()).save(any());
        verify(surveyRepository, never()).save(any());
        verify(recordChangeService, never()).valueCleared(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("broj u poruci je broj zapisa koji stvarno gube vrijednost, ne svih zapisa")
    void messageCountsOnlyAffectedRecords() {
        service = service();
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(
                new ColumnEntry("oib", "string", null)));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of(
                // broj prolazi kao broj...
                survey(1L, Map.of("oib", 12345)),
                // ...a TEKST ne, ni kad izgleda kao broj. Tako je 12.08. i nastala steta:
                // vrijednosti upisane izvan aplikacije bile su tekst u brojcanom stupcu.
                survey(2L, Map.of("oib", "00000000002")),
                // zapis koji taj stupac uopce ne ima nema sto izgubiti
                survey(3L, Map.of("drugi", "x"))
        ));

        assertThatThrownBy(() -> service.update(TEMPLATE_ID, "oib",
                changedToUnconfirmed("oib", "number", null)))
                .isInstanceOf(ColumnDataLossException.class)
                .hasMessageContaining("1 zapis ostaje");
    }

    @Test
    @DisplayName("izmjena koja nista ne gubi ne trazi potvrdu")
    void harmlessChangeNeedsNoConfirmation() {
        service = service();
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(
                new ColumnEntry("grad", "string", null)));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of(
                survey(1L, Map.of("grad", "Split"))
        ));

        // preimenovanje: vrijednost seli pod novi kljuc, nista se ne gubi
        assertThatCode(() -> service.update(TEMPLATE_ID, "grad",
                changedToUnconfirmed("mjesto", "string", null)))
                .doesNotThrowAnyException();

        verify(recordChangeService, never()).valueCleared(any(), any(), any(), any(), any());
    }

    /**
     * Trag je druga polovica popravka: bez njega je izmjena sheme bila jedini nacin da podatak
     * nestane bez ijednog zapisa o tome sto je bio. Dnevnik je znao samo BROJ zahvacenih zapisa.
     */
    @Test
    @DisplayName("izgubljena vrijednost ulazi u povijest retka, s tim sto je bila")
    void clearedValueEntersRecordHistory() {
        service = service();
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(
                new ColumnEntry("oib", "string", null)));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of(
                survey(1L, Map.of("oib", "00000000001"))
        ));

        service.update(TEMPLATE_ID, "oib", changedTo("oib", "number", null));

        verify(recordChangeService).valueCleared(1L, TEMPLATE_ID, 3L, "oib", "00000000001");
    }

    @Test
    @DisplayName("preimenovanje stupca mijenja kljuc u svim zapisima, vrijednost ostaje")
    void renamingMigratesKeyInAllSurveys() {
        service = service();
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(new ColumnEntry("grad", "string", null)));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of(
                survey(1L, Map.of("grad", "Split")),
                survey(2L, Map.of("grad", "Zagreb"))
        ));

        service.update(TEMPLATE_ID, "grad", changedTo("mjesto", "string", null));

        assertThat(savedSurveys()).allSatisfy(saved -> assertThat(saved.getData()).doesNotContainKey("grad"));
        assertThat(savedSurveys()).extracting(s -> s.getData().get("mjesto"))
                .containsExactlyInAnyOrder("Split", "Zagreb");
    }

    /**
     * Prebacivanje stupca na DRUGI sifrarnik je isto sto je prije bilo suzavanje popisa:
     * vrijednost koja u novom sifrarniku nema svoju sifru gubi znacenje, pa se cisti.
     */
    @Test
    @DisplayName("prebacivanje na drugi Å¡ifrarnik Äisti Å¡ifru koje u njemu nema")
    void switchingCodebookClearsUnknownCode() {
        service = service();
        codebookHas(8L, "ZG", "OS");
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(
                new ColumnEntry("grad", "codebook", 7L)));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of(
                survey(1L, Map.of("grad", "ST"))
        ));

        // novi sifrarnik nema sifru "ST"
        service.update(TEMPLATE_ID, "grad", changedTo("grad", "codebook", 8L));

        assertThat(savedSurveys()).singleElement()
                .satisfies(saved -> assertThat(saved.getData()).doesNotContainKey("grad"));
    }

    @Test
    @DisplayName("promjena tipa zadrzava vrijednost koja novom tipu odgovara")
    void typeChangeKeepsStillValidValue() {
        service = service();
        codebookHas(7L, "ST", "ZG");
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(
                new ColumnEntry("oznaka", "string", null)));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of(
                survey(1L, Map.of("oznaka", "ZG"))
        ));

        // tekst -> sifrarnik, gdje postojeci tekst slucajno jest jedna od sifara
        service.update(TEMPLATE_ID, "oznaka", changedTo("oznaka", "codebook", 7L));

        assertThat(savedSurveys()).singleElement()
                .satisfies(saved -> assertThat(saved.getData()).containsEntry("oznaka", "ZG"));
    }

    @Test
    @DisplayName("zapisi koji taj stupac nisu popunili se pri uredivanju ne diraju")
    void surveysWithoutTheKeyAreNotTouched() {
        service = service();
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(new ColumnEntry("grad", "string", null)));
        when(surveyRepository.findByTemplateId(TEMPLATE_ID)).thenReturn(List.of(
                survey(1L, Map.of("tim", "backend"))
        ));

        service.update(TEMPLATE_ID, "grad", changedTo("mjesto", "string", null));

        verify(surveyRepository, never()).save(any());
    }

    // ===================== PRILOZI =====================
    // Prilozene datoteke ne zive u jsonb-u zapisa nego u vlastitoj tablici, pa ih migracija
    // vrijednosti (koja radi nad jsonb-om) ne bi ni vidjela - moraju se seliti zasebno.

    @Test
    @DisplayName("preimenovanje stupca za datoteke premjesta i njegove priloge")
    void renamingFileColumnMovesAttachments() {
        service = service();
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(
                new ColumnEntry("dokument", "file", null)));

        service.update(TEMPLATE_ID, "dokument", changedTo("ponuda", "file", null));

        // bez ovoga bi prilozi ostali vezani uz naziv kojeg vise nema - nevidljivi svakom
        // ekranu, pa ih se ne bi moglo ni procitati ni obrisati
        verify(attachmentCleanup).renameColumn(TEMPLATE_ID, "dokument", "ponuda");
        verify(attachmentCleanup, never()).forColumn(any(), any());
    }

    @Test
    @DisplayName("prelazak stupca s datoteka na drugi tip brise njegove priloge")
    void changingFileColumnToAnotherTypeRemovesAttachments() {
        service = service();
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(
                new ColumnEntry("dokument", "file", null)));

        service.update(TEMPLATE_ID, "dokument", changedTo("dokument", "string", null));

        // ista odluka kao ciscenje vrijednosti koje novom tipu ne odgovaraju: stupac koji
        // vise nije "file" nema gdje prikazati priloge
        verify(attachmentCleanup).forColumn(TEMPLATE_ID, "dokument");
    }

    @Test
    @DisplayName("izmjena stupca koji nikad nije bio za datoteke ne dira priloge")
    void editingOrdinaryColumnLeavesAttachmentsAlone() {
        service = service();
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template(
                new ColumnEntry("grad", "string", null)));

        service.update(TEMPLATE_ID, "grad", changedTo("mjesto", "string", null));

        verify(attachmentCleanup, never()).renameColumn(any(), any(), any());
        verify(attachmentCleanup, never()).forColumn(any(), any());
    }
}
