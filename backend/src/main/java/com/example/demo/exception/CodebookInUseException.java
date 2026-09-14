package com.example.demo.exception;

/**
 * Sifrarnik (ili njegova stavka) se ne moze obrisati jer se negdje koristi.
 *
 * Poruka uvijek imenuje KONKRETNO mjesto - obrazac i stupac, odnosno sifru koja je
 * upisana u zapis. "Ne moze se obrisati jer se koristi" bez toga ostavlja korisnika da
 * sam pretrazi sve obrasce ne bi li nasao krivca.
 *
 * Za stavku koja se vise ne zeli nuditi, a upisana je u stare zapise, ispravan potez
 * nije brisanje nego iskljucivanje ({@code active = false}) - zato to poruka i predlaze.
 */
public class CodebookInUseException extends RuntimeException {
    public CodebookInUseException(String message) {
        super(message);
    }
}
