package com.example.demo.dto;

import java.util.Map;

/**
 * Jedan zapis kakav se nudi u dijalogu za odabir veze, i kakav se prikazuje u celiji
 * referentnog stupca.
 *
 * Celiji trebaju samo dvije stvari - sto se sprema ({@code id}) i sto covjek vidi
 * ({@code label}). Dijalogu za odabir treba i {@code data}: u njemu se vidi CIJELI redak, jer
 * jedan stupac cesto ne razlikuje dva zapisa ("Nabava" i "Nabava" iz razlicitih godina).
 *
 * Zato {@code data} popunjava samo ponuda za dijalog; razrjesavanje naziva za tablicu
 * ({@code named}) ostavlja null i ostaje jednako lagano kao prije - ono se poziva za svaku
 * stranicu tablice, a dijalog se otvara na zahtjev.
 *
 * {@code label} se racuna pri svakom citanju iz stupca koji je referentni stupac odredio kao
 * naziv; nigdje se ne sprema. Kopija naziva bi bila brza, ali bi zastarjela cim se ciljani
 * zapis preimenuje - a u alatu za usklađenost zastarjeli naziv nije nespretnost nego kvar.
 *
 * @param id    id zapisa - ono sto stoji u {@code data} onoga tko vezu drzi
 * @param label naziv zapisa; kad ga nema, {@code #12 (bez naziva)} - a kad zapisa vise nema,
 *              {@code #12 (obrisan zapis)}. Goli {@code #12} je ono sto celija pokazuje DOK
 *              ceka odgovor, pa se s tim oblikom ne smije poklopiti.
 * @param data  vrijednosti zapisa, za prikaz cijelog retka u dijalogu; {@code null} ondje gdje
 *              se traze samo nazivi. Sirove su, bez formatiranja - oblik odreduje shema, koju
 *              preglednik ionako ima.
 */
public record RecordOptionResponse(Long id, String label, Map<String, Object> data) {

    /** Samo id i naziv - za celiju tablice, kojoj ostatak retka ne treba. */
    public RecordOptionResponse(Long id, String label) {
        this(id, label, null);
    }
}
