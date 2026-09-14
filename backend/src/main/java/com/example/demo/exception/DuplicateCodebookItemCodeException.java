package com.example.demo.exception;

/**
 * Ista sifra dvaput unutar jednog sifrarnika.
 *
 * Bazu cuva jedinstveno ogranicenje {@code (codebook_id, code)}, ali ono hvata samo
 * doslovno iste nizove. Ovu iznimku baca servis i za razlike u velicini slova
 * ("OPEN" i "open"), jer bi korisniku to bila ista sifra - isto pravilo kakvo
 * SchemaValidator primjenjuje na jedinstvene stupce.
 */
public class DuplicateCodebookItemCodeException extends RuntimeException {
    public DuplicateCodebookItemCodeException(String code) {
        super("Šifra \"" + code + "\" se pojavljuje više puta u istom šifrarniku.");
    }
}
