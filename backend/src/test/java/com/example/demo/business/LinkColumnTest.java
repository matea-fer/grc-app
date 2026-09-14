package com.example.demo.business;

import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.dto.CreateColumnDefinitionRequest;
import com.example.demo.exception.InvalidColumnDefinitionException;
import com.example.demo.exception.SchemaValidationException;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - stupac tipa "Poveznice": popis adresa koje nosi sam ZAPIS.
 *
 * Poveznica nije postavka sheme nego vrijednost retka, kao i prilog: administrator kaze da
 * stupac postoji, a sto u njemu stoji odlucuje onaj tko unosi podatke. Zato je tezisste ovih
 * testova na SADRZAJU, a ne na deklaraciji.
 *
 * Kljucno pravilo koje se ovdje brani je sigurnosno: adresu upisuje jedan korisnik, a klikne
 * je drugi. Bez granice na http(s) bi unos podataka postao nacin da se izvrsi kod kod kolege
 * koji taj zapis otvori.
 */
@Tag("business")
class LinkColumnTest {

    private static final long TEMPLATE_ID = 10L;

    private final SurveyResultRepository surveyRepository = mock(SurveyResultRepository.class);
    private final SchemaValidator validator =
            new SchemaValidator(mock(CodebookItemRepository.class), surveyRepository);

    // --- sadrzaj zapisa ---

    private void validate(Object value) {
        Map<String, Object> data = new HashMap<>();
        data.put("dokazi", value);
        validator.validate(data, List.of(new ColumnDefinitionResponse("dokazi", "link", null)));
    }

    private static Map<String, Object> link(String url, String name) {
        Map<String, Object> link = new HashMap<>();
        link.put("url", url);
        if (name != null) {
            link.put("name", name);
        }
        return link;
    }

    @Test
    @DisplayName("popis poveznica s nazivom i bez njega prolazi")
    void acceptsLinks() {
        assertThatCode(() -> validate(List.of(
                link("https://intranet/politika.pdf", "Politika sigurnosti"),
                link("http://intranet/zapisnik", null))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prazan popis prolazi - zapis jos nema nijedan dokaz")
    void acceptsEmptyList() {
        assertThatCode(() -> validate(List.of())).doesNotThrowAnyException();
    }

    /**
     * Ovo je razlog zasto se sadrzaj uopce provjerava na serveru, a ne samo u pregledniku:
     * preglednik je zadnja crta, ali nije jedina, i ne brani onoga tko upis posalje mimo njega.
     */
    @Test
    @DisplayName("adresa koja nije http(s) se odbija")
    void rejectsDangerousScheme() {
        assertThatThrownBy(() -> validate(List.of(link("javascript:alert(document.cookie)", "Dokaz"))))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("http");
    }

    @Test
    @DisplayName("poveznica bez adrese se odbija")
    void rejectsLinkWithoutUrl() {
        assertThatThrownBy(() -> validate(List.of(link("   ", "Dokaz"))))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("address");
    }

    @Test
    @DisplayName("jedna poveznica umjesto popisa se odbija")
    void rejectsSingleValue() {
        assertThatThrownBy(() -> validate("https://intranet/x"))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("list of links");
    }

    /** Polje koje nitko ne cita bi u zapisu bilo tiha neistina - isto pravilo kao u shemi. */
    @Test
    @DisplayName("nepoznato polje u poveznici se odbija")
    void rejectsUnknownField() {
        Map<String, Object> link = link("https://intranet/x", "Dokaz");
        link.put("onclick", "alert(1)");

        assertThatThrownBy(() -> validate(List.of(link)))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("unknown field");
    }

    @Test
    @DisplayName("naziv mora biti tekst")
    void rejectsNonTextName() {
        Map<String, Object> link = new HashMap<>();
        link.put("url", "https://intranet/x");
        link.put("name", 42);

        assertThatThrownBy(() -> validate(List.of(link)))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("link name");
    }

    @Test
    @DisplayName("predugacka adresa se odbija")
    void rejectsHugeUrl() {
        assertThatThrownBy(() -> validate(List.of(link("https://x/" + "a".repeat(2100), null))))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("longer than");
    }

    // --- deklaracija stupca ---

    private ColumnDefinitionService service() {
        TemplateService templateService = mock(TemplateService.class);
        Template template = new Template(3L, "Kontrole");
        template.setId(TEMPLATE_ID);
        template.setDefinitions(new ArrayList<>());
        when(templateService.requireEditable(TEMPLATE_ID)).thenReturn(template);

        return new ColumnDefinitionService(templateService, surveyRepository, mock(CodebookService.class),
                validator, new FormulaEvaluator(), mock(AttachmentCleanup.class), mock(LogService.class),
                mock(RecordChangeService.class));
    }

    private static ColumnOptions withLabel(String buttonLabel) {
        return new ColumnOptions(false, null, null, null, null, null, null, null, false, false,
                null, buttonLabel, null);
    }

    private CreateColumnDefinitionRequest column(boolean unique, String defaultValue) {
        return new CreateColumnDefinitionRequest("dokazi", "link", null, "Dokazi", false, unique,
                defaultValue, false, null, withLabel("Dokazi"));
    }

    /** Natpis dijeli s gumbom i prilogom - kod sva tri je celija gumb. */
    @Test
    @DisplayName("stupac s natpisom na gumbu prolazi")
    void acceptsColumnWithLabel() {
        assertThatCode(() -> service().create(TEMPLATE_ID, column(false, null)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("popis ne moze biti jedinstven ni imati zadanu vrijednost")
    void rejectsUniqueAndDefault() {
        assertThatThrownBy(() -> service().create(TEMPLATE_ID, column(true, null)))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("popis");

        assertThatThrownBy(() -> service().create(TEMPLATE_ID, column(false, "https://x")))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("popis");
    }

    /** Postavke drugih tipova bi i ovdje bile tvrdnja koju nista ne provodi. */
    @Test
    @DisplayName("uzorak se ne moze postaviti na stupac s poveznicama")
    void rejectsForeignOption() {
        ColumnOptions withPattern = new ColumnOptions(false, null, null, null, null, "^x$", null,
                null, false, false, null, "Dokazi", null);

        assertThatThrownBy(() -> service().create(TEMPLATE_ID,
                new CreateColumnDefinitionRequest("dokazi", "link", null, "Dokazi", false, false,
                        null, false, null, withPattern)))
                .isInstanceOf(InvalidColumnDefinitionException.class)
                .hasMessageContaining("Uzorak");
    }
}
