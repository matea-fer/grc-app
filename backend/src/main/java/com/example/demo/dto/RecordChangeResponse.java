package com.example.demo.dto;

import com.example.demo.model.RecordChange;

import java.time.Instant;

/**
 * Jedan redak dijaloga "Povijest".
 *
 * Uz spremljeni {@code columnKey} ide i {@code columnLabel} razrijesen iz TRENUTNE sheme.
 * Spremljen je samo kljuc, jer je on ono sto je u trenutku promjene bilo istina; naziv je
 * stvar prikaza i smije se mijenjati. Kad stupca vise nema (obrisan je), naziv ostaje sam
 * kljuc - povijest tada i dalje pokazuje da je polje postojalo i sto se u njemu dogodilo.
 */
public record RecordChangeResponse(
        Instant changedAt,
        String username,
        String columnKey,
        String columnLabel,
        String oldValue,
        String newValue
) {
    public static RecordChangeResponse from(RecordChange change, String columnLabel) {
        return new RecordChangeResponse(change.getChangedAt(), change.getUsername(),
                change.getColumnKey(), columnLabel, change.getOldValue(), change.getNewValue());
    }
}
