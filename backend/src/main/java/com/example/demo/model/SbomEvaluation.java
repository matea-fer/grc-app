package com.example.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Jedna evaluacija SBOM-a: uploadana datoteka + rezultat pozadinske analize Grypeom.
 *
 * {@code companyId} veze evaluaciju uz firmu (izolacija kao i svugdje). Sam popis
 * ranjivosti stoji kao JSON tekst u {@code vulnerabilities} - to je izlaz vanjskog
 * alata, ne nasa domenska shema, pa se ne rasclanjuje u stupce; brojevi po ozbiljnosti
 * se izracunaju pri analizi i cuvaju odvojeno, da popis evaluacija ne mora citati JSON.
 */
@Entity
@Table(
        name = "sbom_evaluation",
        indexes = {
                @Index(name = "ix_sbom_evaluation_company", columnList = "company_id"),
                @Index(name = "ix_sbom_evaluation_product", columnList = "product_id")
        }
)
public class SbomEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /** Zapis-produkt (survey_result.id) uz koji je SBOM vezan; null za samostalnu evaluaciju. */
    @Column(name = "product_id")
    private Long productId;

    /** Naziv produkta u trenutku uploada - da ekran ima naziv i kad se produkt kasnije promijeni. */
    @Column(name = "product_name")
    private String productName;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private SbomStatus status;

    @Column(name = "uploaded_by")
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Verzija Grypea koja je analizu napravila (iz descriptor.version izlaza). */
    @Column(name = "tool_version", length = 64)
    private String toolVersion;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "critical_count", nullable = false)
    private int criticalCount;

    @Column(name = "high_count", nullable = false)
    private int highCount;

    @Column(name = "medium_count", nullable = false)
    private int mediumCount;

    @Column(name = "low_count", nullable = false)
    private int lowCount;

    @Column(name = "negligible_count", nullable = false)
    private int negligibleCount;

    @Column(name = "unknown_count", nullable = false)
    private int unknownCount;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    /** Popis ranjivosti kao JSON niz (SbomVulnerability[]); null dok analiza ne zavrsi. */
    @Column(name = "vulnerabilities", columnDefinition = "text")
    private String vulnerabilities;

    public SbomEvaluation() {
    }

    public SbomEvaluation(Long companyId, String fileName, String uploadedBy) {
        this.companyId = companyId;
        this.fileName = fileName;
        this.uploadedBy = uploadedBy;
        this.status = SbomStatus.PENDING;
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

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public SbomStatus getStatus() {
        return status;
    }

    public void setStatus(SbomStatus status) {
        this.status = status;
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

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getToolVersion() {
        return toolVersion;
    }

    public void setToolVersion(String toolVersion) {
        this.toolVersion = toolVersion;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(int totalCount) {
        this.totalCount = totalCount;
    }

    public int getCriticalCount() {
        return criticalCount;
    }

    public void setCriticalCount(int criticalCount) {
        this.criticalCount = criticalCount;
    }

    public int getHighCount() {
        return highCount;
    }

    public void setHighCount(int highCount) {
        this.highCount = highCount;
    }

    public int getMediumCount() {
        return mediumCount;
    }

    public void setMediumCount(int mediumCount) {
        this.mediumCount = mediumCount;
    }

    public int getLowCount() {
        return lowCount;
    }

    public void setLowCount(int lowCount) {
        this.lowCount = lowCount;
    }

    public int getNegligibleCount() {
        return negligibleCount;
    }

    public void setNegligibleCount(int negligibleCount) {
        this.negligibleCount = negligibleCount;
    }

    public int getUnknownCount() {
        return unknownCount;
    }

    public void setUnknownCount(int unknownCount) {
        this.unknownCount = unknownCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getVulnerabilities() {
        return vulnerabilities;
    }

    public void setVulnerabilities(String vulnerabilities) {
        this.vulnerabilities = vulnerabilities;
    }
}
