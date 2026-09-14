package com.example.demo.dto;

import com.example.demo.model.ColumnOptions;
import jakarta.validation.constraints.NotBlank;

/**
 * Izmjena postojeceg stupca u templateu. Stari naziv (koji se mijenja) dolazi iz
 * putanje; ovdje je zeljeno novo stanje stupca.
 *
 * {@code columnKey} smije biti isti kao stari (mijenja se samo tip/sifrarnik/pravila)
 * ili nov (preimenovanje - servis tada preimenuje kljuc u svim zapisima templatea).
 *
 * @param confirmDataLoss pozivatelj zna da izmjena zapisima uzima vrijednosti koje novoj
 *                        definiciji ne odgovaraju. Bez ovoga takva izmjena zavrsi kao 409 s
 *                        brojem zahvacenih zapisa - da se "promijenio sam uzorak" i "120
 *                        zapisa je ostalo bez vrijednosti" ne dogadaju istim klikom.
 *
 * Zastavice su {@code Boolean}, ne primitivni {@code boolean} - razlog je isti kao
 * u {@link CreateColumnDefinitionRequest}: na Jacksonu 3 izostavljeno primitivno
 * polje rusi zahtjev prije validacije. Pogadalo je bas {@code confirmDataLoss},
 * koji klijent salje samo kad izmjenu stvarno potvrduje.
 */
public record UpdateColumnDefinitionRequest(
        @NotBlank String columnKey,
        @NotBlank String columnType,
        Long codebookId,
        String label,
        Boolean required,
        Boolean unique,
        String defaultValue,
        Boolean readOnly,
        Integer width,
        ColumnOptions options,
        Boolean confirmDataLoss
) {

    public UpdateColumnDefinitionRequest {
        options = ColumnOptions.orEmpty(options);
        required = Boolean.TRUE.equals(required);
        unique = Boolean.TRUE.equals(unique);
        readOnly = Boolean.TRUE.equals(readOnly);
        // izostavljena potvrda znaci "nisam potvrdio" - izmjena koja uzima vrijednosti
        // tada zavrsi kao 409, sto je i smisao te zastavice
        confirmDataLoss = Boolean.TRUE.equals(confirmDataLoss);
    }

    /** Stupac bez postavki ovisnih o tipu i bez zadane sirine. */
    public UpdateColumnDefinitionRequest(String columnKey, String columnType, Long codebookId, String label,
                                         boolean required, boolean unique, String defaultValue, boolean readOnly) {
        this(columnKey, columnType, codebookId, label, required, unique, defaultValue, readOnly,
                null, ColumnOptions.EMPTY, false);
    }

    /** Izmjena bez potvrde gubitka podataka - onakva kakva je bila prije te zastavice. */
    public UpdateColumnDefinitionRequest(String columnKey, String columnType, Long codebookId, String label,
                                         boolean required, boolean unique, String defaultValue, boolean readOnly,
                                         Integer width, ColumnOptions options) {
        this(columnKey, columnType, codebookId, label, required, unique, defaultValue, readOnly,
                width, options, false);
    }
}
