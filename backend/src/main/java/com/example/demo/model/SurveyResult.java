package com.example.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Jedan redak tablice.
 *
 * Fiksni stupci su iskljucivo infrastrukturni: {@code id}, {@code companyId} i
 * {@code templateId}. Zapis pripada jednom templateu (obrazac + shema), a preko
 * njega i firmi. Sve poslovno je u {@code data} i odredeno je shemom tog templatea
 * ({@link Template}), ne ovim razredom - zato dodavanje novog polja vise ne trazi
 * promjenu koda.
 *
 * {@code companyId} se drzi uz {@code templateId} radi brze izolacije po firmi;
 * postavlja se iz templatea kojem zapis pripada. Sadrzaj {@code data} bazi nista
 * ne znaci; provjerava ga servis pri upisu.
 *
 * Iznimka od "sve poslovno je u data" su {@code lockedAt} i {@code lockedBy}: zakljucanost
 * nije podatak koji obrazac prikuplja nego stanje samog zapisa - ono odlucuje smije li se
 * {@code data} uopce mijenjati. Da stoji u {@code data}, mijenjao bi ga isti PUT koji
 * zakljucavanje treba sprijeciti, a nestao bi i kad bi se gumb maknuo iz sheme.
 */
@Entity
public class SurveyResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long companyId;

    private Long templateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> data = new HashMap<>();

    /**
     * Kada je zapis zakljucan; {@code null} znaci da nije.
     *
     * Isti obrazac kao {@code Company.deletedAt}: jedan stupac odgovara i na "je li" i na
     * "kada", a svi zatecni retci ostaju otkljucani bez ijednog UPDATE-a.
     */
    private Instant lockedAt;

    /** Tko je zapis zakljucao - korisnicko ime, kao i u dnevniku. */
    private String lockedBy;

    public SurveyResult() {
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

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Instant lockedAt) {
        this.lockedAt = lockedAt;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    /** Zakljucan zapis se ne smije mijenjati ni brisati - vidi {@code SurveyResultService}. */
    public boolean isLocked() {
        return lockedAt != null;
    }
}
