package com.example.demo.exception;

/**
 * Prilog koji se ne moze prihvatiti: prazna datoteka, prevelika, ili stupac koji uopce
 * nije predviden za prilaganje. Sve troje je greska POZIVA, ne stanja - zato 400.
 */
public class InvalidAttachmentException extends RuntimeException {

    public InvalidAttachmentException(String message) {
        super(message);
    }
}
