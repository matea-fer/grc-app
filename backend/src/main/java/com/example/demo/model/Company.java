package com.example.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/**
 * Firma (tenant).
 *
 * Brisanje je dvokoracno. {@code deletedAt} je jedina razlika izmedu "aktivna" i
 * "arhivirana": firma s postavljenim vremenom nestaje s popisa, njezini se korisnici ne
 * mogu prijaviti i nijedan zahtjev nad njezinim podacima ne prolazi - ali svi podaci su
 * netaknuti i {@code restore} ih vraca. Fizicki ih uklanja tek zasebna radnja
 * ({@code purge}), koja je dopustena SAMO nad arhiviranom firmom.
 *
 * Time pogresan klik prestaje biti nepovratan, a to je bio cijeli razlog: prije ovoga je
 * brisanje firme odmah i zauvijek uklanjalo njezine korisnike.
 */
@Entity
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /** Vrijeme arhiviranja; null znaci da je firma aktivna. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    public Company() {
    }

    public Company(String name) {
        this.name = name;
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

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isArchived() {
        return deletedAt != null;
    }
}