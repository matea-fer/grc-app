package com.example.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Jedan obrazac (template) - imenovani skup stupaca koji pripada jednoj firmi.
 *
 * Firma ih moze imati vise; svaki template nosi svoje stupce ({@code definitions})
 * i, preko {@link SurveyResult#getTemplateId()}, svoje zapise. Prije je vrijedilo
 * "1 firma = 1 shema" (tada {@code CompanyColumns}); sad je "1 firma = N templatea",
 * a globalnih stupaca (companyId == null) vise nema.
 */
@Entity
@Table(name = "form_template")
public class Template {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false)
    private String name;

    /**
     * Stabilan identifikator obrasca kojim ga developer prepoznaje neovisno o imenu.
     *
     * Ime ({@link #name}) korisnik mijenja kroz rename; kod se ne mijenja - postavlja se
     * jednom pri provisioningu starter obrasca i sluzi kao sidro za migracije nad
     * {@code definitions} i kao odgovor "ima li tenant vec obrazac tipa X" (starter paketi).
     * Obrasci koje je korisnik sam napravio nemaju kod (null). Namjerno NEMA veze s
     * {@link #setName}: preimenovanje ne smije pomaknuti kod.
     */
    @Column(name = "template_code", length = 64)
    private String templateCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<ColumnEntry> definitions = new ArrayList<>();

    /**
     * Je li obrazac UPITNIK: retke (pitanja) definira administrator u Editoru i vide se na
     * svakoj instanci, a korisnik ih ne dodaje ni ne brise - samo bira odgovor (sifrarnik).
     * Obican obrazac (false) radi kao i dosad.
     */
    @Column(nullable = false)
    private boolean questionnaire = false;

    // Cijeli redak se cita, mijenja i pise natrag. Bez ovoga bi dvije
    // istovremene izmjene rezultirale time da druga pregazi prvu ("lost update").
    @Version
    private Long version;

    public Template() {
    }

    public Template(Long companyId, String name) {
        this.companyId = companyId;
        this.name = name;
    }

    /**
     * Obrazac s dodijeljenim stabilnim kodom - koristi provisioning starter obrazaca (Task 4).
     * Obican {@code create} kroz API ide kroz konstruktor bez koda: korisnicki obrasci nemaju kod.
     */
    public Template(Long companyId, String name, String templateCode) {
        this.companyId = companyId;
        this.name = name;
        this.templateCode = templateCode;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTemplateCode() {
        return templateCode;
    }

    /**
     * Postavlja stabilni kod. Zove ga SAMO provisioning starter obrazaca; rename i ostale
     * korisnicke izmjene ga ne diraju, jer kod mora prezivjeti preimenovanje.
     */
    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }

    public List<ColumnEntry> getDefinitions() {
        return definitions;
    }

    public void setDefinitions(List<ColumnEntry> definitions) {
        this.definitions = definitions;
    }

    public boolean isQuestionnaire() {
        return questionnaire;
    }

    public void setQuestionnaire(boolean questionnaire) {
        this.questionnaire = questionnaire;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
