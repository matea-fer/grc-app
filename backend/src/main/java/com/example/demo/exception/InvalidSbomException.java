package com.example.demo.exception;

/**
 * SBOM datoteka koja se ne moze prihvatiti: prazna ili prevelika. Greska POZIVA, ne
 * stanja - zato 400. (Sto Grype nade u sadrzaju nije stvar ove iznimke; neispravan
 * SBOM zavrsi kao FAILED evaluacija s porukom, ne kao odbijen upload.)
 */
public class InvalidSbomException extends RuntimeException {

    public InvalidSbomException(String message) {
        super(message);
    }
}
