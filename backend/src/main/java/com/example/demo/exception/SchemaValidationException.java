package com.example.demo.exception;

import java.util.List;

/**
 * Podaci retka ne odgovaraju shemi firme.
 *
 * Nosi SVE nadene greske, ne samo prvu - korisniku koji je popunio formu s tri
 * krive vrijednosti nema smisla javljati ih jednu po jednu.
 */
public class SchemaValidationException extends RuntimeException {
    private final List<String> errors;

    public SchemaValidationException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
