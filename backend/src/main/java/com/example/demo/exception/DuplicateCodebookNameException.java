package com.example.demo.exception;

/**
 * Sifrarnik tog naziva vec postoji u istom dosegu i istoj firmi.
 *
 * Provjera je u servisu, a ne jedinstveno ogranicenje u bazi: kod GLOBAL sifrarnika
 * je {@code company_id} null, a Postgres NULL vrijednosti u jedinstvenom indeksu
 * smatra razlicitima - pa bi dva globalna sifrarnika istog naziva prosla.
 */
public class DuplicateCodebookNameException extends RuntimeException {
    public DuplicateCodebookNameException(String name) {
        super("Šifrarnik \"" + name + "\" već postoji.");
    }
}
