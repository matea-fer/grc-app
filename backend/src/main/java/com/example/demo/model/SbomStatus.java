package com.example.demo.model;

/**
 * Stanje jedne SBOM evaluacije.
 *
 * PENDING  - zapis je stvoren, analiza jos nije krenula
 * RUNNING  - Grype se izvrsava u pozadini
 * DONE     - analiza zavrsena, rezultat (ranjivosti + brojevi) je spreman
 * FAILED   - analiza nije uspjela; razlog je u {@code errorMessage}
 */
public enum SbomStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED
}
