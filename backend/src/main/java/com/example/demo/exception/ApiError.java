package com.example.demo.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Greska kakvu vidi klijent.
 *
 * {@code code} je strojna oznaka i postavlja se samo ondje gdje klijent po gresci mora nesto
 * ODLUCITI, a ne samo je ispisati. Zasad postoji jedna takva: izmjena stupca koja bi zapisima
 * uzela vrijednosti vraca 409, a Editor iz toga mora znati da smije pitati "nastaviti?" i
 * ponoviti poziv s potvrdom. Prepoznavati to po statusu 409 ne bi islo - isti status vraca i
 * duplikat naziva stupca, kod kojeg pitanje nema smisla; prepoznavati po tekstu poruke znacilo
 * bi da promjena teksta tiho pokvari ponasanje.
 *
 * Kad koda nema, polje se ne pojavljuje u JSON-u ({@code JsonInclude.NON_NULL}), pa stari
 * odgovori ostaju kakvi su i bili.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String message, String code) {

    /** Oznaka za izmjenu stupca koja zapisima uzima vrijednosti - vidi {@link ColumnDataLossException}. */
    public static final String CODE_COLUMN_DATA_LOSS = "COLUMN_DATA_LOSS";

    /**
     * Oznaka za brisanje zapisa na koji pokazuju veze - vidi {@link RecordReferencedException}.
     *
     * Klijent po njoj zna da je poruka POPIS onih koji smetaju, pa je prikazuje u obliku koji
     * se da procitati (vise redaka), a ne kao jednu recenicu u traci greske.
     */
    public static final String CODE_RECORD_REFERENCED = "RECORD_REFERENCED";

    /** Obicna greska - klijent je samo ispisuje. */
    public ApiError(String message) {
        this(message, null);
    }
}
