package com.example.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Jedna promjena jednog POLJA jednog zapisa - revizijski trag.
 *
 * Odnos prema {@link LogEntry} je podjela posla, ne udvostrucenje: dnevnik biljezi da je
 * zapis promijenjen (jedna akcija - jedan redak), a ovo biljezi sto se u njemu promijenilo
 * (jedna izmjena - po jedan redak za svako dirnuto polje). Prvo odgovara na "tko je i kada
 * radio", drugo na "kako je ova vrijednost postala ovakva".
 *
 * {@code oldValue} je null kad polje prije nije imalo vrijednost (npr. pri stvaranju zapisa),
 * a {@code newValue} kad je vrijednost obrisana. Oboje null se ne biljezi - to nije promjena.
 */
@Entity
@Table(name = "record_change")
public class RecordChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "survey_id", nullable = false)
    private Long surveyId;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    // nosi firmu izravno, da se povijest moze obrisati pri praznjenju firme bez prolaska
    // kroz obrasce i zapise kojih tada vise nema
    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /**
     * Kljuc stupca u trenutku promjene. NE mijenja se kad se stupac kasnije preimenuje -
     * trag govori kako se polje tada zvalo.
     */
    @Column(name = "column_key", nullable = false)
    private String columnKey;

    @Column(name = "old_value", length = 1000)
    private String oldValue;

    @Column(name = "new_value", length = 1000)
    private String newValue;

    // tko je promijenio; null samo ako se promjena dogodi izvan prijavljene sesije
    @Column(name = "username")
    private String username;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    public RecordChange() {
    }

    public RecordChange(Long surveyId, Long templateId, Long companyId, String columnKey,
                        String oldValue, String newValue, String username, Instant changedAt) {
        this.surveyId = surveyId;
        this.templateId = templateId;
        this.companyId = companyId;
        this.columnKey = columnKey;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.username = username;
        this.changedAt = changedAt;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSurveyId() {
        return surveyId;
    }

    public void setSurveyId(Long surveyId) {
        this.surveyId = surveyId;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }

    public String getColumnKey() {
        return columnKey;
    }

    public void setColumnKey(String columnKey) {
        this.columnKey = columnKey;
    }

    public String getOldValue() {
        return oldValue;
    }

    public void setOldValue(String oldValue) {
        this.oldValue = oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public void setNewValue(String newValue) {
        this.newValue = newValue;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(Instant changedAt) {
        this.changedAt = changedAt;
    }
}
