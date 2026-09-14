package com.example.demo.model;

/**
 * Doseg sifrarnika - komu njegove vrijednosti pripadaju.
 *
 * <ul>
 *   <li>{@code GLOBAL} - zajednicki rjecnik za sve firme (drzave, valute, mjerne
 *       jedinice). {@code companyId} je {@code null}. Ureduje ga samo globalni
 *       administrator; citaju ga svi.</li>
 *   <li>{@code TENANT} - sifrarnik jedne firme. {@code companyId} je obavezan.</li>
 * </ul>
 *
 * Popisi su NAMJERNO odvojeni: firma ne moze dodavati stavke u globalni sifrarnik
 * niti ih skrivati. Da moze, svako citanje stavki moralo bi spajati dva izvora,
 * {@code sortOrder} bi bio dvoznacan, a jedinstvenost sifre unutar sifrarnika
 * prestala bi vrijediti onako kako pise - sifra koju doda jedna firma blokirala bi
 * istu sifru drugoj. To je isti razlog zbog kojeg su ranije maknuti globalni stupci
 * (vidi {@link Template}).
 *
 * Cijena te odluke: firmi kojoj u globalnom sifrarniku nedostaje vrijednost ne
 * preostaje nego napraviti vlastiti. Duplikacija je svjesno prihvacena.
 */
public enum CodebookScope {
    GLOBAL,
    TENANT
}
