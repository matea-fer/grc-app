package com.example.demo.exception;

/**
 * Izmjena stupca bi zapisima uzela vrijednosti, a pozivatelj to nije potvrdio -> 409.
 *
 * Poruka nosi BROJ zahvacenih zapisa, jer je to jedini podatak zbog kojeg se ovo i postavlja:
 * "izmijenit ces stupac" i "120 zapisa ostaje bez vrijednosti" su dvije bitno razlicite
 * radnje, a do sada su izgledale isto.
 *
 * Zasto 409 i ponovni poziv, a ne zaseban endpoint za pregled: broj se racuna iz ISTE nove
 * definicije koja se sprema, pa bi ga zaseban poziv morao dobiti drugim putem i mogao bi se
 * razici s onim sto se na kraju izvrsi.
 */
public class ColumnDataLossException extends RuntimeException {

    private final int affectedRecords;

    public ColumnDataLossException(String message, int affectedRecords) {
        super(message);
        this.affectedRecords = affectedRecords;
    }

    public int getAffectedRecords() {
        return affectedRecords;
    }
}
