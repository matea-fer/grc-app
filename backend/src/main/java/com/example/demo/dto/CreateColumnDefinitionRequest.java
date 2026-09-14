package com.example.demo.dto;

import com.example.demo.model.ColumnOptions;
import jakarta.validation.constraints.NotBlank;

/**
 * Firma za koju se stupac deklarira NE dolazi iz tijela - uzima se iz tenant
 * konteksta (zaglavlja), pa je jedna firma nikad ne moze deklarirati drugoj.
 *
 * Obavezan je samo naziv i tip; {@code label} i {@code defaultValue} smiju izostati
 * (prazan label znaci "prikazi kljuc"), a zastavice koje nisu poslane su false.
 * {@code codebookId} je obavezan tocno kad je tip "codebook", i mora pokazivati na
 * sifrarnik koji je toj firmi vidljiv (njezin vlastiti ili globalni).
 *
 * {@code options} nosi postavke koje ovise o tipu; smije izostati (stupac bez posebnosti).
 * Postavka koja se odabranom tipu ne tice se odbija - vidi {@code validateDefinition}.
 *
 * Zastavice su {@code Boolean}, a ne primitivni {@code boolean}, i to je nuzno na
 * Spring Bootu 4: Jackson 3 ukljucuje {@code FAIL_ON_NULL_FOR_PRIMITIVES}, pa
 * izostavljeno polje vise ne postaje {@code false} nego RUSI zahtjev jos pri
 * rasclanjivanju JSON-a - prije ijedne validacije, s odgovorom 500 umjesto 400.
 * Na Jacksonu 2 isti kod radi, pa se zamka vidi tek nakon podizanja verzije.
 */
public record CreateColumnDefinitionRequest(
        @NotBlank String columnKey,
        @NotBlank String columnType,
        Long codebookId,
        String label,
        Boolean required,
        Boolean unique,
        String defaultValue,
        Boolean readOnly,
        Integer width,
        ColumnOptions options
) {

    public CreateColumnDefinitionRequest {
        options = ColumnOptions.orEmpty(options);
        // izostavljena zastavica znaci "ne", kako i pise gore - normalizira se ovdje
        // da ostatak koda nikad ne vidi null
        required = Boolean.TRUE.equals(required);
        unique = Boolean.TRUE.equals(unique);
        readOnly = Boolean.TRUE.equals(readOnly);
    }

    /** Stupac bez postavki ovisnih o tipu i bez zadane sirine. */
    public CreateColumnDefinitionRequest(String columnKey, String columnType, Long codebookId, String label,
                                         boolean required, boolean unique, String defaultValue, boolean readOnly) {
        this(columnKey, columnType, codebookId, label, required, unique, defaultValue, readOnly,
                null, ColumnOptions.EMPTY);
    }
}
