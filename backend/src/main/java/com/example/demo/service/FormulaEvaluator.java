package com.example.demo.service;

import com.example.demo.dto.ColumnDefinitionResponse;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Racuna vrijednost stupca tipa "formula" iz drugih stupaca istog retka.
 *
 * Izraz podrzava {@code + - * / ( )}, brojeve i pozivanje na stupac u uglatim zagradama:
 * {@code [cijena] * [kolicina]}. Zagrade nisu ukras - naziv stupca smije sadrzavati razmak
 * ("neto cijena"), pa bi goli naziv bio nerazlucitiv od dva odvojena imena.
 *
 * Racuna se NA SERVERU I PRI SPREMANJU, a rezultat se sprema u zapis kao obican broj.
 * Alternativa (racunanje tek pri prikazu) znacila bi da se po stupcu ne moze pretrazivati
 * ni sortirati, i da isti izraz mora postojati i u pregledniku i na serveru - dvije
 * izvedbe koje se s vremenom razidu.
 *
 * Ovdje nema biblioteke za izraze: gramatika je toliko mala da je vlastiti parser kraci
 * od konfiguracije bilo koje biblioteke, a i jedina je ovisnost koju bi projekt dobio
 * samo zbog ovog jednog stupca.
 */
@Component
public class FormulaEvaluator {

    /** Poziv na stupac, citan isto kao u parseru: sve do prve zatvorene zagrade. */
    private static final Pattern REFERENCE = Pattern.compile("\\[([^\\]]*)\\]");

    /** Dijeljenje daje decimalni rezultat, pa mu treba zadana preciznost (1/3 nema kraj). */
    private static final MathContext DIVISION = new MathContext(12, RoundingMode.HALF_UP);

    /**
     * Nazivi stupaca na koje se izraz poziva, redom kojim se pojavljuju.
     *
     * @throws IllegalArgumentException ako izraz nije ispravan; poruka je hrvatska jer je
     *                                  vidi onaj tko formulu upisuje u Editoru
     */
    public List<String> references(String formula) {
        Node parsed = new Parser(formula).parse();
        List<String> keys = new ArrayList<>();
        parsed.collect(keys);
        return keys;
    }

    /**
     * Isti izraz, ali s pozivima na {@code oldKey} prepisanima u {@code newKey}.
     *
     * Postoji zbog preimenovanja stupca: bez ovoga bi formula ostala pokazivati na kljuc kojeg
     * vise nema, i to TIHO - stupac bi se pri sljedecem spremanju zapisa jednostavno ispraznio.
     * Preimenovanje ionako migrira kljuceve u svim zapisima, pa je prirodno da povuce i formule.
     *
     * Prepisuju se samo cijeli pozivi, ne dijelovi teksta: {@code [a]} se mijenja, a
     * {@code [ab]} ne. Zato uzorak mora citati zagrade jednako kao i parser - sve do prve
     * zatvorene, uz obrezane razmake.
     */
    public String rename(String formula, String oldKey, String newKey) {
        if (formula == null || oldKey.equals(newKey)) {
            return formula;
        }
        Matcher matcher = REFERENCE.matcher(formula);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            matcher.appendReplacement(result,
                    Matcher.quoteReplacement(key.equals(oldKey) ? "[" + newKey + "]" : matcher.group()));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** Samo provjeri da se izraz da procitati. @throws IllegalArgumentException uz opis problema */
    public void requireParsable(String formula) {
        new Parser(formula).parse();
    }

    /**
     * Vrijednost izraza za jedan redak.
     *
     * @return izracunata vrijednost, ili {@code null} kad se ne moze izracunati (stupac na
     *         koji se poziva je prazan ili nije broj, ili se dijeli s nulom). Prazno je
     *         ovdje ispravan ishod, a ne greska: redak se sprema i s nepopunjenim ulazima,
     *         pa bi rusenje spremanja zbog prazne formule blokiralo unos u pola posla.
     */
    public BigDecimal evaluate(String formula, Map<String, Object> values) {
        try {
            return new Parser(formula).parse().value(values);
        } catch (ArithmeticException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Izracunaj sve formule u retku i vrati podatke s upisanim rezultatima.
     *
     * Formule se racunaju redom ovisnosti, jer se jedna smije pozivati na drugu
     * ({@code [ukupno] = [neto] * 1.25}, gdje je i {@code [neto]} formula). Stupac koji
     * u tom redoslijedu ne moze doci na red (kruzna ovisnost) se preskace - shema takvo
     * stanje odbija jos pri definiranju, pa je ovo samo zastita od zateceni podataka.
     */
    public Map<String, Object> computeAll(List<ColumnDefinitionResponse> schema, Map<String, Object> data) {
        List<ColumnDefinitionResponse> formulas = schema.stream()
                .filter(column -> "formula".equals(column.columnType()))
                .toList();
        if (formulas.isEmpty()) {
            return data;
        }

        Map<String, Object> result = new LinkedHashMap<>(data);
        for (ColumnDefinitionResponse column : inDependencyOrder(formulas)) {
            String formula = column.options().formula();
            BigDecimal value = formula == null || formula.isBlank() ? null : evaluate(formula, result);
            if (value == null) {
                result.remove(column.columnKey());
            } else {
                result.put(column.columnKey(), value.stripTrailingZeros());
            }
        }
        return result;
    }

    /**
     * Stupci s formulama poredani tako da svaki dolazi nakon onih na koje se poziva.
     * Stupci koji ostanu u krugu se izostavljaju - vidi {@link #computeAll}.
     */
    private List<ColumnDefinitionResponse> inDependencyOrder(List<ColumnDefinitionResponse> formulas) {
        Map<String, ColumnDefinitionResponse> byKey = new LinkedHashMap<>();
        for (ColumnDefinitionResponse column : formulas) {
            byKey.put(column.columnKey(), column);
        }

        List<ColumnDefinitionResponse> ordered = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        // najvise onoliko prolaza koliko ima stupaca: svaki prolaz rijesi barem jedan,
        // inace su preostali u krugu i nema ih smisla dalje vrtjeti
        for (int pass = 0; pass < formulas.size() && ordered.size() < formulas.size(); pass++) {
            for (ColumnDefinitionResponse column : formulas) {
                if (done.contains(column.columnKey()) || !dependenciesReady(column, byKey, done)) {
                    continue;
                }
                ordered.add(column);
                done.add(column.columnKey());
            }
        }
        return ordered;
    }

    /** Jesu li sve formule na koje se ovaj stupac poziva vec izracunate. */
    private boolean dependenciesReady(ColumnDefinitionResponse column,
                                      Map<String, ColumnDefinitionResponse> formulasByKey,
                                      Set<String> done) {
        String formula = column.options().formula();
        if (formula == null || formula.isBlank()) {
            return true;
        }
        List<String> referenced;
        try {
            referenced = references(formula);
        } catch (IllegalArgumentException e) {
            return true; // neispravnu formulu nema smisla cekati; evaluate ce vratiti null
        }
        for (String key : referenced) {
            // stupac koji nije formula je obican podatak - on je uvijek "spreman"
            if (formulasByKey.containsKey(key) && !done.contains(key)) {
                return false;
            }
        }
        return true;
    }

    // ===================== PARSER =====================

    /** Cvor razapetog izraza. Stablo se gradi jednom, pa se moze i procitati i izracunati. */
    private interface Node {
        BigDecimal value(Map<String, Object> values);

        void collect(List<String> keys);
    }

    /**
     * Rekurzivni parser silaskom, po gramatici:
     * <pre>
     *   izraz  := clan (('+'|'-') clan)*
     *   clan   := faktor (('*'|'/') faktor)*
     *   faktor := '-' faktor | broj | '[' naziv ']' | '(' izraz ')'
     * </pre>
     * Mnozenje vezuje jace od zbrajanja upravo zato sto je na nizoj razini gramatike.
     */
    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text == null ? "" : text;
        }

        Node parse() {
            if (text.isBlank()) {
                throw new IllegalArgumentException("formula je prazna");
            }
            Node node = expression();
            skipSpaces();
            if (pos < text.length()) {
                throw new IllegalArgumentException("neocekivan znak \"" + text.charAt(pos) + "\" na mjestu " + (pos + 1));
            }
            return node;
        }

        private Node expression() {
            Node left = term();
            while (true) {
                skipSpaces();
                char op = peek();
                if (op != '+' && op != '-') {
                    return left;
                }
                pos++;
                left = binary(op, left, term());
            }
        }

        private Node term() {
            Node left = factor();
            while (true) {
                skipSpaces();
                char op = peek();
                if (op != '*' && op != '/') {
                    return left;
                }
                pos++;
                left = binary(op, left, factor());
            }
        }

        private Node factor() {
            skipSpaces();
            char c = peek();
            if (c == '-') {
                pos++;
                Node inner = factor();
                return node(values -> {
                    BigDecimal v = inner.value(values);
                    return v == null ? null : v.negate();
                }, inner::collect);
            }
            if (c == '(') {
                pos++;
                Node inner = expression();
                skipSpaces();
                if (peek() != ')') {
                    throw new IllegalArgumentException("nedostaje zatvorena zagrada \")\"");
                }
                pos++;
                return inner;
            }
            if (c == '[') {
                return reference();
            }
            if (Character.isDigit(c) || c == '.' || c == ',') {
                return number();
            }
            throw new IllegalArgumentException(pos >= text.length()
                    ? "formula zavrsava prerano"
                    : "neocekivan znak \"" + c + "\" na mjestu " + (pos + 1));
        }

        /** Poziv na stupac: {@code [naziv]}. Naziv smije sadrzavati sve osim zatvorene zagrade. */
        private Node reference() {
            int close = text.indexOf(']', pos);
            if (close < 0) {
                throw new IllegalArgumentException("nedostaje zatvorena uglata zagrada \"]\"");
            }
            String key = text.substring(pos + 1, close).trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("prazan naziv stupca u uglatim zagradama");
            }
            pos = close + 1;
            return node(values -> toNumber(values.get(key)), keys -> keys.add(key));
        }

        /** Decimalna tocka i zarez se oboje prihvacaju - upisuje ih covjek, ne stroj. */
        private Node number() {
            int start = pos;
            while (pos < text.length() && (Character.isDigit(text.charAt(pos))
                    || text.charAt(pos) == '.' || text.charAt(pos) == ',')) {
                pos++;
            }
            String raw = text.substring(start, pos).replace(',', '.');
            BigDecimal value;
            try {
                value = new BigDecimal(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("\"" + raw + "\" nije broj");
            }
            return node(values -> value, keys -> { });
        }

        private Node binary(char op, Node left, Node right) {
            return node(values -> {
                BigDecimal a = left.value(values);
                BigDecimal b = right.value(values);
                // nepopunjen ulaz znaci da rezultata nema; nula bi bila izmisljen podatak
                if (a == null || b == null) {
                    return null;
                }
                return switch (op) {
                    case '+' -> a.add(b);
                    case '-' -> a.subtract(b);
                    case '*' -> a.multiply(b);
                    // dijeljenje s nulom nije greska sheme nego prazan rezultat za taj redak
                    default -> b.signum() == 0 ? null : a.divide(b, DIVISION);
                };
            }, keys -> {
                left.collect(keys);
                right.collect(keys);
            });
        }

        private char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        private void skipSpaces() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private Node node(java.util.function.Function<Map<String, Object>, BigDecimal> value,
                          java.util.function.Consumer<List<String>> collect) {
            return new Node() {
                @Override
                public BigDecimal value(Map<String, Object> values) {
                    return value.apply(values);
                }

                @Override
                public void collect(List<String> keys) {
                    collect.accept(keys);
                }
            };
        }
    }

    /** Vrijednost iz retka kao broj, ili null ako je prazna ili nije broj. */
    private static BigDecimal toNumber(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim().replace(',', '.'));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
