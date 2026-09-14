package com.example.demo.dto;

import com.example.demo.model.ColumnEntry;
import com.example.demo.model.ColumnOptions;

/**
 * Jedan stupac kakvim ga vidi frontend.
 *
 * Nema {@code id} ni {@code companyId} - stupac zivi kao element JSON niza unutar
 * jednog templatea, pa ga jednoznacno odreduje par (templateId, columnKey), a
 * templateId je vec u putanji. Globalnih stupaca (vrijede za sve firme) vise nema.
 *
 * Uz tip i sifrarnik nosi i pravila unosa: {@code required}, {@code unique},
 * {@code readOnly} te {@code defaultValue} kojim se forma predpopunjava. Sve sto ovisi
 * o tipu stupca je u {@link ColumnOptions}.
 *
 * {@code ColumnOptions} je model razred, a ne zaseban DTO. Namjerno: on je cisti record
 * bez ijedne veze s bazom, pa bi njegov blizanac u ovom paketu bio samo jos jedno mjesto
 * koje se mora mijenjati pri svakom novom atributu stupca.
 *
 * Stavke sifrarnika se ovdje NE salju - frontend ih dohvaca s {@code /api/codebooks/{id}/items},
 * jednom po sifrarniku, umjesto da se isti popis ponavlja uz svaki stupac koji ga koristi.
 */
public record ColumnDefinitionResponse(
        String columnKey,
        String columnType,
        Long codebookId,
        String label,
        boolean required,
        boolean unique,
        String defaultValue,
        boolean readOnly,
        Integer width,
        ColumnOptions options
) {

    /** Postavke se normaliziraju odmah - vidi {@link ColumnEntry}. */
    public ColumnDefinitionResponse {
        options = ColumnOptions.orEmpty(options);
    }

    /** Stupac bez postavki ovisnih o tipu - vidi {@link ColumnEntry#ColumnEntry(String, String, Long, String, boolean, boolean, String, boolean)}. */
    public ColumnDefinitionResponse(String columnKey, String columnType, Long codebookId, String label,
                                    boolean required, boolean unique, String defaultValue, boolean readOnly) {
        this(columnKey, columnType, codebookId, label, required, unique, defaultValue, readOnly,
                null, ColumnOptions.EMPTY);
    }

    /** Stupac bez dodatnih atributa - vidi {@link ColumnEntry#ColumnEntry(String, String, Long)}. */
    public ColumnDefinitionResponse(String columnKey, String columnType, Long codebookId) {
        this(columnKey, columnType, codebookId, null, false, false, null, false);
    }

    public static ColumnDefinitionResponse from(ColumnEntry entry) {
        return new ColumnDefinitionResponse(entry.key(), entry.type(), entry.codebookId(), entry.label(),
                entry.required(), entry.unique(), entry.defaultValue(), entry.readOnly(),
                entry.width(), entry.options());
    }

    /** Naziv za korisnika: {@code label} ako postoji, inace sam kljuc. */
    public String displayLabel() {
        return label == null || label.isBlank() ? columnKey : label;
    }

    /** Pokazuje li ovaj stupac na zapis drugog obrasca. */
    public boolean isReference() {
        return "reference".equals(columnType);
    }

    /**
     * Sadrzi li ovaj stupac vise vrijednosti odjednom (niz umjesto jedne vrijednosti).
     *
     * Vrijedi za oba tipa koja biraju iz gotovog popisa - sifrarnik nosi niz sifara, referenca
     * niz id-eva. Postavka je ista ({@code multiple}), pa je i pitanje jedno: sve sto radi s
     * "vise vrijednosti odjednom" (provjera, sortiranje, ispis u povijesti) ne treba znati
     * KOJI od ta dva tipa gleda.
     */
    public boolean isMultiValued() {
        return options.multiple() && ("codebook".equals(columnType) || isReference());
    }
}
