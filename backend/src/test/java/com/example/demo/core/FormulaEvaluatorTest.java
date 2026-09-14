package com.example.demo.core;

import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.model.ColumnOptions;
import com.example.demo.service.FormulaEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CORE test - sam racun formule, bez sheme i bez baze.
 *
 * Tema je gramatika i ono sto se dogada kad racun ne ide: nepopunjen ulaz i dijeljenje
 * nulom NISU greske nego prazan rezultat, jer se redak mora moci spremiti i dok nije
 * dovrsen. Pravila o tome koja je formula uopce dopustena (na koje se stupce smije
 * pozivati, smije li se vrtjeti u krug) su u ColumnTypeOptionsTest.
 */
@Tag("core")
class FormulaEvaluatorTest {

    private final FormulaEvaluator evaluator = new FormulaEvaluator();

    private static ColumnDefinitionResponse formulaColumn(String key, String formula) {
        return new ColumnDefinitionResponse(key, "formula", null, null, false, false, null, true, null,
                new ColumnOptions(false, null, null, null, null, null, null, null, false, false, formula, null, null));
    }

    @Test
    @DisplayName("mnozenje vezuje jace od zbrajanja, zagrade to mijenjaju")
    void respectsPrecedenceAndParentheses() {
        Map<String, Object> row = Map.of("a", 2, "b", 3, "c", 4);

        assertThat(evaluator.evaluate("[a] + [b] * [c]", row)).isEqualByComparingTo("14");
        assertThat(evaluator.evaluate("([a] + [b]) * [c]", row)).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("naziv stupca smije sadrzavati razmak - zato uglate zagrade")
    void supportsKeysWithSpaces() {
        Map<String, Object> row = Map.of("neto cijena", 100, "kolicina", 3);

        assertThat(evaluator.evaluate("[neto cijena] * [kolicina]", row)).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("decimalni broj se prihvaca i sa zarezom i s tockom")
    void acceptsBothDecimalSeparators() {
        Map<String, Object> row = Map.of("neto", 100);

        assertThat(evaluator.evaluate("[neto] * 1,25", row)).isEqualByComparingTo("125");
        assertThat(evaluator.evaluate("[neto] * 1.25", row)).isEqualByComparingTo("125");
    }

    @Test
    @DisplayName("nepopunjen ulaz daje prazan rezultat, a ne nulu")
    void missingInputGivesNoResult() {
        // nula bi bila izmisljen podatak: "jos nije upisano" nije isto sto i "iznosi nula"
        assertThat(evaluator.evaluate("[cijena] * [kolicina]", Map.of("cijena", 10))).isNull();
    }

    @Test
    @DisplayName("dijeljenje nulom daje prazan rezultat umjesto greske")
    void divisionByZeroGivesNoResult() {
        // redak s nulom u nazivniku mora se moci spremiti - inace bi se zaglavio na pola unosa
        assertThat(evaluator.evaluate("[a] / [b]", Map.of("a", 10, "b", 0))).isNull();
    }

    @Test
    @DisplayName("vrijednost upisana kao tekst se i dalje racuna")
    void parsesNumericText() {
        assertThat(evaluator.evaluate("[a] + 1", Map.of("a", "41"))).isEqualByComparingTo("42");
        assertThat(evaluator.evaluate("[a] + 1", Map.of("a", "nije broj"))).isNull();
    }

    @Test
    @DisplayName("neispravan izraz se prijavljuje pri citanju, ne pri racunanju")
    void reportsSyntaxErrors() {
        assertThatThrownBy(() -> evaluator.references("[a] * "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evaluator.references("([a] + [b]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zatvorena zagrada");
        assertThatThrownBy(() -> evaluator.references(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("references nabraja stupce na koje se izraz poziva")
    void listsReferencedColumns() {
        assertThat(evaluator.references("([cijena] - [popust]) * [kolicina]"))
                .containsExactly("cijena", "popust", "kolicina");
    }

    @Test
    @DisplayName("formula koja se poziva na drugu formulu racuna se nakon nje")
    void computesInDependencyOrder() {
        // "ukupno" ovisi o "neto", a u shemi stoji PRIJE njega - redoslijed u shemi ne smije
        // odlucivati o ishodu racuna
        List<ColumnDefinitionResponse> schema = List.of(
                formulaColumn("ukupno", "[neto] * 1,25"),
                formulaColumn("neto", "[cijena] * [kolicina]"),
                new ColumnDefinitionResponse("cijena", "number", null),
                new ColumnDefinitionResponse("kolicina", "number", null));

        Map<String, Object> result = evaluator.computeAll(schema, Map.of("cijena", 10, "kolicina", 4));

        assertThat((BigDecimal) result.get("neto")).isEqualByComparingTo("40");
        assertThat((BigDecimal) result.get("ukupno")).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("formula bez rezultata brise staru vrijednost iz zapisa")
    void clearsStaleResult() {
        // ulaz je u meduvremenu ispraznjen; ostaviti staru vrijednost znacilo bi da stupac
        // pokazuje racun koji vise ne stoji
        List<ColumnDefinitionResponse> schema = List.of(
                formulaColumn("ukupno", "[cijena] * [kolicina]"),
                new ColumnDefinitionResponse("cijena", "number", null));

        Map<String, Object> result = evaluator.computeAll(schema, Map.of("ukupno", 999, "cijena", 10));

        assertThat(result).doesNotContainKey("ukupno");
    }
}
