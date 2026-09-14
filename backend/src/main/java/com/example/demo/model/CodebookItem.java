package com.example.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Jedna vrijednost unutar sifrarnika.
 *
 * Dva naziva koja se lako pomijese:
 *   {@code code} - tehnicka, STABILNA sifra koja se sprema u podatke ("OPEN"),
 *   {@code name} - citljiv naziv koji korisnik vidi ("Otvoren") i koji se smije mijenjati.
 *
 * Zato se {@code code} i ne bi smio mijenjati nakon prve upotrebe: zapisi koji ga
 * vec nose ne bi znali na sto pokazuju.
 *
 * Neaktivna stavka ({@code active = false}) se NE nudi za nove unose, ali ostaje
 * citljiva u starim zapisima - zato se stavke skrivaju, a ne brisu. Isti razlog
 * zbog kojeg migracija stupca cuva vrijednosti koje moze sacuvati.
 *
 * Veza prema roditelju je obican {@code Long}, a ne {@code @ManyToOne} - tako je i
 * {@code SurveyResult.templateId} i {@code Template.companyId}, pa se cijeli projekt
 * drzi jednog nacina.
 */
@Entity
@Table(
        name = "codebook_item",
        // Sifra je jedinstvena UNUTAR jednog sifrarnika, ne preko cijele tablice:
        // dvije firme smiju imati istu sifru u svojim sifrarnicima. Oba stupca su
        // NOT NULL, pa ogranicenje vrijedi doslovno (kod nullable stupca Postgres
        // NULL vrijednosti smatra razlicitima, pa bi duplikati prosli).
        uniqueConstraints = @UniqueConstraint(
                name = "uk_codebook_item_codebook_code",
                columnNames = {"codebook_id", "code"})
)
public class CodebookItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "codebook_id", nullable = false)
    private Long codebookId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    // nova stavka je aktivna dok se izricito ne sakrije
    @Column(nullable = false)
    private boolean active = true;

    // Redoslijed u padajucem izborniku. Server ga pri skupnom spremanju prenumerira
    // po polozaju u poslanom nizu, pa klijent ne mora slati suvisle brojeve - samo
    // ispravan redoslijed.
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public CodebookItem() {
    }

    public CodebookItem(Long codebookId, String code, String name, boolean active, int sortOrder) {
        this.codebookId = codebookId;
        this.code = code;
        this.name = name;
        this.active = active;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCodebookId() {
        return codebookId;
    }

    public void setCodebookId(Long codebookId) {
        this.codebookId = codebookId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
