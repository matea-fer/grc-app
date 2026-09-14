package com.example.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Zapis o jednoj izvrsenoj akciji u aplikaciji (audit log).
 *
 * Pune ga servisi kad akcija uspije. {@code action} je strojno citljiva oznaka
 * (npr. {@code COLUMN_UPDATED}), a {@code detail} je ljudski opis STO je tocno
 * dirano (naziv, id, sto se promijenilo). {@code username} je TKO, a
 * {@code companyId} nad cijom firmom je akcija izvrsena.
 *
 * Oba mogu biti null, i to znaci razlicite stvari: {@code username} je null samo
 * za zapise nastale prije uvodenja prijave, a {@code companyId} za akcije koje se
 * ne ticu nijedne firme (npr. neuspjela prijava nepoznatim korisnickim imenom).
 * Zapisi o samim firmama se namjerno vezu uz firmu koju diraju, da bi se vidjeli
 * u njezinu dnevniku.
 *
 * Biljeze se samo IZMJENE i prijave, nikad citanja: citanja brojcano zdrobe sve
 * ostalo, pa bi dnevnik prestao biti citljiv.
 */
@Entity
@Table(name = "log_table")
public class LogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // firma nad kojom je akcija izvrsena; null = akcija se ne tice nijedne firme
    @Column(name = "company_id")
    private Long companyId;

    // tko je akciju izvrsio; null samo za zapise nastale prije uvodenja prijave
    @Column(name = "username")
    private String username;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false, length = 1000)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public LogEntry() {
    }

    public LogEntry(Long companyId, String username, String action, String detail, Instant createdAt) {
        this.companyId = companyId;
        this.username = username;
        this.action = action;
        this.detail = detail;
        this.createdAt = createdAt;
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
