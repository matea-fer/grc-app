package com.example.demo.service;

/**
 * Pitanje "ima li vec neki DRUGI redak ovu vrijednost u ovom stupcu".
 *
 * Postoji da {@link SchemaValidator} ne mora dobiti podatke svih ostalih redaka. Dok ih je
 * dobivao, svako spremanje jednog zapisa ucitavalo je cijeli obrazac u memoriju - na 20.000
 * zapisa dakle 20.000 dokumenata po kliku "Spremi", i to zbog pitanja na koje baza odgovara
 * jednim upitom koji staje kod prvog pogotka.
 *
 * Validator time ostaje neovisan o bazi: u testovima se predaje obicna lambda, a u pogonu
 * upit ({@code SurveyQueryRepository.existsWith...}).
 */
@FunctionalInterface
public interface UniqueValueLookup {

    /** Nema s cim usporediti - koristi se ondje gdje jedinstvenost ne moze biti tema. */
    UniqueValueLookup NONE = (columnKey, value) -> false;

    boolean isTaken(String columnKey, Object value);
}
