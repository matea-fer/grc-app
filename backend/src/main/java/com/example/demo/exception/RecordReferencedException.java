package com.example.demo.exception;

/**
 * Zapis se ne moze obrisati jer na njega pokazuju veze iz drugih obrazaca -> 409.
 *
 * Poruka NOSI POPIS onih koji smetaju, i to je cijeli smisao ove iznimke. Zabrana bez odgovora
 * "tko smeta" je slijepa ulica: korisnik vidi da ne moze, ali ne zna sto bi popravio. S popisom
 * je to obican zadatak - otvori te zapise, prevezi ih ili obrisi, pa ponovi.
 *
 * Zasto 409, a ne 403: nije stvar ovlasti (nitko to ne bi smio, ni administrator) nego stanja -
 * dok veza postoji, brisanje je u sukobu s podacima. Isti razlog kao kod zakljucanog zapisa.
 *
 * Zasto zabrana, a ne tiho brisanje veza: veza koja tiho nestane je izgubljen podatak, a u
 * alatu za usklađenost je upravo veza (rizik -> proces, kontrola -> imovina) ono sto se
 * dokazuje. Stupac koji to ne zeli moze traziti prekid veze ({@code onTargetDelete = "clear"}),
 * ali to mora biti izricita odluka onoga tko definira shemu.
 */
public class RecordReferencedException extends RuntimeException {

    public RecordReferencedException(String message) {
        super(message);
    }
}
