package com.example.demo.exception;

/**
 * Sifrarnik ili njegova stavka ne drze se pravila koja se ne daju izraziti anotacijom
 * na jednom polju - npr. TENANT doseg bez odabrane firme, ili prazna sifra.
 */
public class InvalidCodebookException extends RuntimeException {
    public InvalidCodebookException(String message) {
        super(message);
    }
}
