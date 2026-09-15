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
