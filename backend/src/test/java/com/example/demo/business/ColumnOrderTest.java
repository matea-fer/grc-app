package com.example.demo.business;

import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.dto.ReorderColumnsRequest;
import com.example.demo.exception.InvalidColumnDefinitionException;
import com.example.demo.model.ColumnEntry;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - redoslijed stupaca obrasca.
 *
 * Redoslijed je i dosad odlucivao kako izgleda tablica podataka, ali se mogao mijenjati samo
 * brisanjem i ponovnim dodavanjem stupca - dakle uz gubitak vrijednosti. Ovdje se brani ono
 * sto ga cini bezopasnim: da premjestanje ne dira nijedan zapis, i da se odbije kad se popis
 * ne poklapa sa shemom - jer bi tada tiho promijenio nesto drugo od onoga sto je korisnik
 * vidio na ekranu.
 */
@Tag("business")
class ColumnOrderTest {

    private static final long TEMPLATE_ID = 10L;

    private final TemplateService templateService = mock(TemplateService.class);
    private final SurveyResultRepository surveyRepository = mock(SurveyResultRepository.class);
    private final AttachmentCleanup attachmentCleanup = mock(AttachmentCleanup.class);
    private final LogService logService = mock(LogService.class);
    private final RecordChangeService recordChangeService = mock(RecordChangeService.class);

    private final ColumnDefinitionService service = new ColumnDefinitionService(
            templateService, surveyRepository, mock(CodebookService.class),
            new SchemaValidator(mock(CodebookItemRepository.class), surveyRepository),
            new FormulaEvaluator(), attachmentCleanup, logService, recordChangeService);

    private Template template;

    @BeforeEach
    void seed() {
        template = new Template(3L, "Kontrole");
        template.setId(TEMPLATE_ID);
        template.setDefinitions(new ArrayList<>(List.of(
                new ColumnEntry("naziv", "string", null),
                new ColumnEntry("vlasnik", "string", null),
                new ColumnEntry("rok", "date", null))));
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template);
    }

    private List<String> reorder(String... keys) {
        return service.reorder(TEMPLATE_ID, new ReorderColumnsRequest(List.of(keys)))
                .stream().map(ColumnDefinitionResponse::columnKey).toList();
    }

    @Test
    @DisplayName("stupci se poredaju kako je zatrazeno")
    void appliesNewOrder() {
        assertThat(reorder("rok", "naziv", "vlasnik")).containsExactly("rok", "naziv", "vlasnik");
        assertThat(template.getDefinitions()).extracting(ColumnEntry::key)
                .containsExactly("rok", "naziv", "vlasnik");
    }

    /**
     * Poredak stupaca je stvar PRIKAZA - u zapisima kljucevi ostaju isti, pa se ne migrira
     * nista. To je i razlog zasto ova radnja ne treba potvrdu gubitka podataka, za razliku od
     * izmjene stupca.
     */
    @Test
    @DisplayName("nijedan zapis se ne dira")
    void leavesRecordsAlone() {
        reorder("rok", "vlasnik", "naziv");

        verify(surveyRepository, never()).saveAll(any());
        verify(attachmentCleanup, never()).forColumn(anyLong(), anyString());
        // vrijednost se nije promijenila, pa u povijest zapisa nema sto uci
        verify(recordChangeService, never())
                .valueCleared(any(), any(), any(), anyString(), any());
    }

    /**
     * Manjak bi znacio da stupac ispada iz sheme - a s njim, pri sljedecem citanju, i pristup
     * vrijednostima koje zapisi jos drze. Popis dolazi s ekrana koji je mozda otvoren od jucer,
     * pa je to stvarna mogucnost, a ne teorija.
     */
    @Test
    @DisplayName("popis bez jednog stupca se odbija")
    void rejectsMissingColumn() {
        assertThatThrownBy(() -> reorder("naziv", "vlasnik"))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("ne poklapa");
        assertThat(template.getDefinitions()).hasSize(3);
    }

    @Test
    @DisplayName("stupac kojeg u obrascu nema se odbija")
    void rejectsUnknownColumn() {
        assertThatThrownBy(() -> reorder("naziv", "vlasnik", "rok", "izmisljeni"))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("ne poklapa");
    }

    @Test
    @DisplayName("isti stupac dvaput se odbija")
    void rejectsDuplicate() {
        assertThatThrownBy(() -> reorder("naziv", "naziv", "vlasnik"))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("dvaput");
    }

    /** Promjena sheme, pa i ova, ide u dnevnik - inace se ne zna tko je promijenio prikaz. */
    @Test
    @DisplayName("promjena redoslijeda ostaje zabiljezena")
    void writesToLog() {
        reorder("rok", "naziv", "vlasnik");

        verify(logService).record("COLUMNS_REORDERED",
                "Redoslijed stupaca obrasca id=10: rok, naziv, vlasnik");
    }
}
