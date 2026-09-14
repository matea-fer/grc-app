package com.example.demo.exception;

/** Zahtjev nije prijavljen (nema tokena, token je neispravan ili je istekao) -> 401. */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
