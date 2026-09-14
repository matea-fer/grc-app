package com.example.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

/**
 * Zaglavlje sifrarnika - imenovani popis dopustenih vrijednosti koji dijeli vise
 * obrazaca. Same vrijednosti su u {@link CodebookItem}.
 *
 * Za razliku od {@link Template#getDefinitions()}, gdje stavke zive kao jsonb niz
 * unutar roditelja, ovdje su stavke ZASEBNA TABLICA. Razlog nije stil nego to sto
 * je {@code code} stavke kljuc na koji se pozivaju drugi podaci: on zavrsi upisan
 * u zapise obrazaca, pa mu duplikat ne kvari sifrarnik nego znacenje vec spremljenih
 * podataka, unatrag. Takav kljuc zasluzuje pravo ogranicenje u bazi, a jsonb ga ne
 * moze dati (vidi komentar u SurveyResultService.create).
 *
 * {@code companyId} prati {@code scope} i to je pravilo koje baza ne moze izraziti
 * (GLOBAL -> mora biti null, TENANT -> mora biti postavljen), pa ga provjerava servis.
 * Firma se pritom NIKAD ne uzima iz tijela zahtjeva, nego iz tenant konteksta - isto
 * kao kod obrazaca i zapisa.
 */
@Entity
public class Codebook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // kao String, ne kao redni broj: dodavanje nove vrijednosti u enum tako ne
    // pomice znacenje vec spremljenim retcima (isto kao Role)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CodebookScope scope;

    // null iskljucivo za GLOBAL doseg
    @Column(name = "company_id")
    private Long companyId;

    /**
     * Cuva cjelinu "sifrarnik + njegove stavke" od dvije istovremene izmjene.
     *
     * Stavke su u vlastitoj tablici, pa ih promjena sama po sebi ne bi dotakla ovaj
     * redak - zato ga skupno spremanje stavki mora izricito osvjeziti. Bez toga bi
     * dva paralelna spremanja popisa (koje je puna zamjena, ne spajanje) zavrsila
     * tako da drugo pregazi prvo, bez ijednog traga.
     */
    @Version
    private Long version;

    public Codebook() {
    }

    public Codebook(String name, CodebookScope scope, Long companyId) {
        this.name = name;
        this.scope = scope;
        this.companyId = companyId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public CodebookScope getScope() {
        return scope;
    }

    public void setScope(CodebookScope scope) {
        this.scope = scope;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
