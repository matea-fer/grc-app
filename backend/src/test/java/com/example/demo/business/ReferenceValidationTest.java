package com.example.demo.business;

import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.exception.SchemaValidationException;
import com.example.demo.model.ColumnOptions;
import com.example.demo.repository.CodebookItemRepository;
import com.example.demo.repository.SurveyResultRepository;
import com.example.demo.service.SchemaValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BUSINESS test - vrijednost referentnog stupca mora pokazivati na POSTOJECI zapis
 * CILJANOG obrasca.
 *
 * Ovo je jedina obrana koja postoji: veza je broj unutar jsonb dokumenta, a ne stupac tablice,
 * pa Postgres ne zna da je to strani kljuc i nema ga tko provoditi. Da ove provjere nema, u
 * zapis bi se mogao upisati bilo koji broj - ukljucujuci id zapisa iz sasvim drugog obrasca.
 */
@Tag("business")
class ReferenceValidationTest {

    private static final long TARGET_ID = 20L;

    private final CodebookItemRepository codebookItems = mock(CodebookItemRepository.class);
    private final SurveyResultRepository surveys = mock(SurveyResultRepository.class);
    private final SchemaValidator validator = new SchemaValidator(codebookItems, surveys);

    private ColumnDefinitionResponse reference(boolean multiple) {
        ColumnOptions options = new ColumnOptions(false, null, null, null, null, null, null, null,
                multiple, false, null, null, null, TARGET_ID, "naziv", "restrict");
        return new ColumnDefinitionResponse("proces", "reference", null, null, false, false,
                null, false, null, options);
    }

    private void exists(long id, boolean exists) {
        lenient().when(surveys.existsByIdAndTemplateId(id, TARGET_ID)).thenReturn(exists);
    }

    @Test
    @DisplayName("id postojeceg zapisa ciljanog obrasca prolazi")
    void acceptsExistingRecord() {
        exists(5L, true);

        assertThatCode(() -> validator.validate(Map.of("proces", 5), List.of(reference(false))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("id kojem u ciljanom obrascu nema zapisa se odbija")
    void rejectsMissingRecord() {
        exists(999L, false);

        assertThatThrownBy(() -> validator.validate(Map.of("proces", 999), List.of(reference(false))))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("does not exist");
    }

    /**
     * Zapis moze postojati, ali u DRUGOM obrascu - a to je veza koja pokazuje na krivu vrstu
     * stvari. Zato se pita {@code existsByIdAndTemplateId}, a ne obicni {@code existsById}.
     */
    @Test
    @DisplayName("id zapisa iz drugog obrasca se odbija")
    void rejectsRecordFromAnotherTemplate() {
        when(surveys.existsByIdAndTemplateId(5L, TARGET_ID)).thenReturn(false);

        assertThatThrownBy(() -> validator.validate(Map.of("proces", 5), List.of(reference(false))))
                .isInstanceOf(SchemaValidationException.class);
    }

    @Test
    @DisplayName("tekst umjesto broja se odbija")
    void rejectsTextValue() {
        assertThatThrownBy(() -> validator.validate(Map.of("proces", "Nabava"), List.of(reference(false))))
                .isInstanceOf(SchemaValidationException.class)
                .hasMessageContaining("record id");
    }

    @Test
    @DisplayName("visevrijednosna veza provjerava svaki id iz popisa")
    void checksEveryIdInList() {
        exists(5L, true);
        exists(6L, false);

        assertThatCode(() -> validator.validate(Map.of("proces", List.of(5)), List.of(reference(true))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(Map.of("proces", List.of(5, 6)), List.of(reference(true))))
                .isInstanceOf(SchemaValidationException.class);
    }

    /**
     * Prazno polje nije veza koja pokazuje u prazno nego polje koje korisnik nije dirao.
     * Da se ovdje ne preskace, svaki bi neispunjen obrazac padao na stupcu koji nije obavezan.
     */
    @Test
    @DisplayName("nepopunjena veza prolazi kad stupac nije obavezan")
    void allowsEmptyValue() {
        assertThatCode(() -> validator.validate(Map.of("proces", ""), List.of(reference(false))))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(Map.of("proces", List.of()), List.of(reference(true))))
                .doesNotThrowAnyException();
    }

    /**
     * Stupac bez ciljanog obrasca je greska SHEME, ne podataka - upis se ne blokira, jednako
     * kao kod sifrarnickog stupca bez sifrarnika. Inace bi pogresno definiran stupac zakljucao
     * unos svih zapisa tog obrasca.
     */
    @Test
    @DisplayName("veza bez ciljanog obrasca ne blokira upis")
    void ignoresReferenceWithoutTarget() {
        ColumnOptions empty = ColumnOptions.EMPTY;
        var column = new ColumnDefinitionResponse("proces", "reference", null, null, false, false,
                null, false, null, empty);

        assertThatCode(() -> validator.validate(Map.of("proces", 5), List.of(column)))
                .doesNotThrowAnyException();
    }
}
