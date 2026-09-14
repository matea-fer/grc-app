package com.example.demo.exception;

/**
 * Prije je jedinstvenost naziva stupca cuvala baza (jedan red = jedan stupac).
 * Unutar JSON niza baza ne provjerava nista, pa je ta provjera presla ovamo -
 * naziv mora biti jedinstven unutar jednog templatea.
 */
public class DuplicateColumnKeyException extends RuntimeException {
    public DuplicateColumnKeyException(String columnKey, Long templateId) {
        super("Column \"" + columnKey + "\" already exists in template " + templateId);
    }
}
