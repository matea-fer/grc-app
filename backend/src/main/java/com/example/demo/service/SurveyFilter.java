package com.example.demo.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Jedan uvjet pretrage, vec preveden iz onoga sto je korisnik upisao u ono sto baza moze
 * usporediti.
 *
 * Prijevod se dogada u {@link SurveyResultService} (ondje je shema i sifrarnik), a upit ga
 * sastavlja u {@link com.example.demo.repository.SurveyQueryRepository}. Granica je namjerno
 * ovdje: servis zna STO korisnik trazi, repozitorij zna KAKO se to pita bazu. Da filtar putuje
 * kao sirovi tekst, repozitorij bi morao citati shemu i sifrarnike - dakle raditi posao
 * servisa, i to iz sloja koji ne zna nista o tome cija je firma.
 *
 * @param columnKey kljuc stupca u jsonb dokumentu retka
 * @param kind      kako se usporeduje - odredeno TIPOM stupca, ne unosom
 * @param text      tekst za {@code TEXT} (vec ociscen od znakova za uzorak), inace null
 * @param number    broj za {@code NUMBER}, inace null
 * @param codes     sifre za {@code CODES} (barem jedna; prazan popis znaci "nema pogodaka" i
 *                  do upita uopce ne dolazi), inace null
 * @param recordId  id povezanog zapisa za {@code REFERENCE}, inace null
 */
public record SurveyFilter(
        String columnKey,
        Kind kind,
        String text,
        BigDecimal number,
        List<String> codes,
        Long recordId
) {

    public enum Kind {
        /** Sadrzi upisani tekst, bez obzira na velika slova. */
        TEXT,
        /** Jednak upisanom broju - po VRIJEDNOSTI, pa su 1 i 1.0 isti. */
        NUMBER,
        /** Ima bilo koju od navedenih sifara (i kad stupac drzi popis sifara). */
        CODES,
        /**
         * Pokazuje na zadani zapis (i kad stupac drzi popis id-eva).
         *
         * Trazi se TOCAN id, ne tekst: vezu korisnik bira iz dijaloga, pa nema sto pogadati.
         * Ovim istim uvjetom radi i dijalog "Povezani zapisi" - ondje je pitanje "tko pokazuje
         * na mene", sto je isti upit gledan s druge strane.
         */
        REFERENCE
    }

    public static SurveyFilter text(String columnKey, String text) {
        return new SurveyFilter(columnKey, Kind.TEXT, text, null, null, null);
    }

    public static SurveyFilter number(String columnKey, BigDecimal number) {
        return new SurveyFilter(columnKey, Kind.NUMBER, null, number, null, null);
    }

    public static SurveyFilter codes(String columnKey, List<String> codes) {
        return new SurveyFilter(columnKey, Kind.CODES, null, null, List.copyOf(codes), null);
    }

    public static SurveyFilter reference(String columnKey, Long recordId) {
        return new SurveyFilter(columnKey, Kind.REFERENCE, null, null, null, recordId);
    }
}
