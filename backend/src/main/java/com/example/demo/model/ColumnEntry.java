package com.example.demo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Jedan stupac unutar JSON niza koji pripada jednom obrascu.
 *
 * Ovo NIJE @Entity - nema svoju tablicu ni id. Postoji samo kao element niza
 * koji se serijalizira u jsonb stupac {@link Template#getDefinitions()}.
 * Zato ga se i ne dohvaca pojedinacno: uvijek se ucita cijeli template,
 * izmijeni u memoriji i spremi natrag.
 *
 * {@code @JsonIgnoreProperties} stoji zbog starijih zapisa: dok su postojali tipovi
 * "select" i "boolean", stupac je nosio i vlastiti ugradeni popis vrijednosti
 * ({@code options}). Migracija ga je maknula, ali jsonb je slobodan oblik - zapis koji
 * je migraciju nekako promasio ne smije srusiti citanje cijelog obrasca.
 *
 * Ovdje su samo atributi koji vrijede za SVAKI tip. Sve sto ovisi o tipu (raspon broja,
 * uzorak teksta, nacin odabira iz sifrarnika...) je u {@link ColumnOptions} - inace bi
 * ovaj record narastao na dvadesetak komponenti koje bi svaki poziv morao nabrojati.
 *
 * @param key          naziv stupca (kljuc u JSON-u retka), jedinstven unutar jednog templatea
 * @param type         string | number | date | codebook | formula | button
 * @param codebookId   sifrarnik iz kojeg dolaze dopustene vrijednosti kad je type = "codebook",
 *                     inace null. U redak se sprema SIFRA stavke, ne njezin naziv - naziv se
 *                     smije mijenjati, sifra ne.
 * @param label        naziv koji vidi korisnik; prazan znaci "koristi key"
 * @param required     redak se ne moze spremiti dok ovo polje nije popunjeno
 * @param unique       vrijednost se ne smije ponoviti ni u jednom drugom retku istog templatea
 * @param defaultValue predpopunjena vrijednost u formi, kao tekst (parsira se prema type)
 * @param readOnly     korisnik ovo polje ne unosi; vrijednost dolazi iz defaultValue i ostaje takva
 * @param width        sirina stupca u tablici zapisa, u pikselima; null = neka odluci preglednik
 * @param options      postavke koje ovise o tipu; nikad null (vidi kompaktni konstruktor)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ColumnEntry(
        String key,
        String type,
        Long codebookId,
        String label,
        boolean required,
        boolean unique,
        String defaultValue,
        boolean readOnly,
        Integer width,
        ColumnOptions options
) {

    /**
     * Stupac ucitan iz starijeg jsonb zapisa nema {@code options} - ondje je null.
     * Normalizira se odmah pri stvaranju, da ostatak koda nikad ne mora provjeravati:
     * "nema postavki" i "ima prazne postavke" su ista stvar, pa ih ne treba razlikovati
     * na dvjestotinjak mjesta.
     */
    public ColumnEntry {
        options = ColumnOptions.orEmpty(options);
    }

    /** Isti stupac s drugim postavkama - koristi se pri prepisivanju formule. */
    public ColumnEntry withOptions(ColumnOptions newOptions) {
        return new ColumnEntry(key, type, codebookId, label, required, unique, defaultValue,
                readOnly, width, newOptions);
    }

    /**
     * Stupac bez postavki ovisnih o tipu i bez zadane sirine - onakav kakvi su svi stupci
     * bili prije nego sto su uvedeni {@link ColumnOptions}.
     */
    public ColumnEntry(String key, String type, Long codebookId, String label, boolean required,
                       boolean unique, String defaultValue, boolean readOnly) {
        this(key, type, codebookId, label, required, unique, defaultValue, readOnly, null, ColumnOptions.EMPTY);
    }

    /**
     * Stupac bez dodatnih atributa - onakav kakvi su svi stupci bili prije nego sto su
     * uvedeni label/required/unique/defaultValue/readOnly. Postoji da stariji jsonb zapisi
     * i pozivi koje ti atributi ne zanimaju ne moraju nabrajati pet praznih vrijednosti.
     */
    public ColumnEntry(String key, String type, Long codebookId) {
        this(key, type, codebookId, null, false, false, null, false);
    }

    /** Naziv za korisnika: {@code label} ako postoji, inace sam kljuc. */
    public String displayLabel() {
        return label == null || label.isBlank() ? key : label;
    }
}
