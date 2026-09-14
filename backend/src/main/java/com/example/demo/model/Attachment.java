package com.example.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Podaci o jednoj prilozenoj datoteci - ime, vrsta, velicina, tko ju je i kad prilozio.
 *
 * SAM SADRZAJ nije ovdje nego u {@link AttachmentContent}, i to je jedina stvar koju
 * treba upamtiti o ovom modelu. Da bajtovi stoje u istoj tablici, svako dohvacanje popisa
 * priloga (a on se crta uz svaki redak tablice) povuklo bi i sve datoteke iz baze. Ovako
 * se sadrzaj ne moze slucajno ucitati - nije ga fizicki moguce dobiti bez drugog upita.
 *
 * Prilog NE ZIVI u jsonb-u zapisa. Stupac tipa "file" u zapisu ne sprema nista; sto je
 * priloženo zna iskljucivo ova tablica. Zapisati to na dva mjesta znacilo bi da se mogu
 * raziici - jsonb koji tvrdi da datoteka postoji, i tablica koja je nema.
 *
 * {@code companyId} se cuva iako se do priloga uvijek dolazi preko obrasca (koji vec nosi
 * firmu): to je druga brava, da pogresan upit ne moze izvuci tudi prilog.
 */
@Entity
@Table(
        name = "attachment",
        // popis priloga jednog zapisa i popis za cijeli obrazac su jedina dva upita
        // koja se stvarno rade, pa oba imaju svoj indeks
        indexes = {
                @Index(name = "ix_attachment_survey", columnList = "survey_id"),
                @Index(name = "ix_attachment_template", columnList = "template_id")
        }
)
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "survey_id", nullable = false)
    private Long surveyId;

    /** Stupac uz koji prilog stoji - jedan zapis smije imati vise stupaca tipa "file". */
    @Column(name = "column_key", nullable = false)
    private String columnKey;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    /**
     * Vrsta datoteke kakvu je javio preglednik. Cuva se samo za prikaz (ikona, opis) -
     * pri preuzimanju se NE koristi, vidi {@code AttachmentController}.
     */
    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Korisnicko ime, a ne id: dnevnik i ostatak projekta prilog vezu uz osobu koja ga vidi. */
    @Column(name = "uploaded_by", nullable = false)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    public Attachment() {
    }

    public Attachment(Long companyId, Long templateId, Long surveyId, String columnKey,
                      String fileName, String contentType, long sizeBytes, String uploadedBy) {
        this.companyId = companyId;
        this.templateId = templateId;
        this.surveyId = surveyId;
        this.columnKey = columnKey;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = Instant.now();
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

    public Long getSurveyId() {
        return surveyId;
    }

    public void setSurveyId(Long surveyId) {
        this.surveyId = surveyId;
    }

    public String getColumnKey() {
        return columnKey;
    }

    public void setColumnKey(String columnKey) {
        this.columnKey = columnKey;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(String uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
