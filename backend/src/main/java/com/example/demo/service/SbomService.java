package com.example.demo.service;

import com.example.demo.auth.AuthContext;
import com.example.demo.dto.SbomEvaluationDetailResponse;
import com.example.demo.dto.SbomEvaluationResponse;
import com.example.demo.dto.SbomVulnerability;
import com.example.demo.exception.InvalidSbomException;
import com.example.demo.exception.ResourceNotFoundException;
import com.example.demo.model.SbomEvaluation;
import com.example.demo.repository.SbomEvaluationRepository;
import com.example.demo.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * SBOM evaluacije jedne firme.
 *
 * Tok: upload spremi zapis (status PENDING) i ODMAH ga vrati, a pravu analizu
 * pokrene u pozadini ({@link GrypeRunner}). Frontend zatim polla status dok ne
 * postane DONE/FAILED. Namjerno BEZ {@code @Transactional} na uploadu: zapis mora
 * biti commitan prije nego pozadinska nit krene, inace ga ona ne bi vidjela.
 *
 * Izolacija po firmi je ista kao svugdje: firma dolazi iz {@link TenantContext}
 * (token, odnosno TenantID za administratora), a tudi id zavrsava kao 404.
 */
@Service
public class SbomService {
    private static final Logger log = LoggerFactory.getLogger(SbomService.class);

    private final SbomEvaluationRepository repository;
    private final GrypeRunner grypeRunner;
    private final TenantContext tenantContext;
    private final AuthContext authContext;
    private final LogService logService;
    private final ObjectMapper objectMapper;
    private final long maxSizeBytes;

    public SbomService(SbomEvaluationRepository repository,
                       GrypeRunner grypeRunner,
                       TenantContext tenantContext,
                       AuthContext authContext,
                       LogService logService,
                       ObjectMapper objectMapper,
                       @Value("${app.sbom.max-file-size-kb:20480}") long maxSizeKb) {
        this.repository = repository;
        this.grypeRunner = grypeRunner;
        this.tenantContext = tenantContext;
        this.authContext = authContext;
        this.logService = logService;
        this.objectMapper = objectMapper;
        this.maxSizeBytes = maxSizeKb * 1024;
    }

    public SbomEvaluationResponse upload(MultipartFile file, Long productId, String productName) {
        Long companyId = tenantContext.require();
        requireAcceptable(file);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new InvalidSbomException("Čitanje datoteke nije uspjelo: " + e.getMessage());
        }

        String fileName = safeFileName(file.getOriginalFilename());
        SbomEvaluation evaluation = new SbomEvaluation(companyId, fileName, authContext.require().username());
        evaluation.setProductId(productId);
        evaluation.setProductName(clip(productName));
        evaluation = repository.save(evaluation);

        // Pokrece se na pozadinskoj niti; zapis je vec commitan (metoda nije @Transactional).
        grypeRunner.run(evaluation.getId(), bytes);

        log.info("SBOM uploadan id={} ({} B), firma={}, produkt={}",
                evaluation.getId(), bytes.length, companyId, productId);
        logService.record("SBOM_UPLOADED",
                "SBOM \"" + fileName + "\" (id=" + evaluation.getId() + ") predan na analizu"
                        + (productId == null ? "" : " za produkt id=" + productId));
        return SbomEvaluationResponse.from(evaluation);
    }

    public List<SbomEvaluationResponse> list(Long productId) {
        Long companyId = tenantContext.require();
        List<SbomEvaluation> rows = productId == null
                ? repository.findByCompanyIdOrderByUploadedAtDescIdDesc(companyId)
                : repository.findByCompanyIdAndProductIdOrderByUploadedAtDescIdDesc(companyId, productId);
        return rows.stream().map(SbomEvaluationResponse::from).toList();
    }

    public SbomEvaluationDetailResponse detail(Long id) {
        Long companyId = tenantContext.require();
        SbomEvaluation e = repository.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("SbomEvaluation", id));
        return new SbomEvaluationDetailResponse(
                e.getId(), e.getProductId(), e.getProductName(), e.getFileName(), e.getStatus(),
                e.getUploadedAt(), e.getFinishedAt(), e.getUploadedBy(), e.getToolVersion(),
                e.getTotalCount(), e.getCriticalCount(), e.getHighCount(), e.getMediumCount(),
                e.getLowCount(), e.getNegligibleCount(), e.getUnknownCount(), e.getErrorMessage(),
                parseVulnerabilities(e.getVulnerabilities()));
    }

    public void delete(Long id) {
        Long companyId = tenantContext.require();
        SbomEvaluation e = repository.findByIdAndCompanyId(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("SbomEvaluation", id));
        repository.delete(e);
        logService.record("SBOM_DELETED",
                "SBOM evaluacija \"" + e.getFileName() + "\" (id=" + id + ") obrisana");
    }

    private List<SbomVulnerability> parseVulnerabilities(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(json, SbomVulnerability[].class));
        } catch (Exception e) {
            // Spremljeni JSON smo sami napisali; ako se ipak ne da procitati, ne rusimo dohvat.
            log.warn("Popis ranjivosti se ne da pročitati (id iz JSON-a): {}", e.getMessage());
            return List.of();
        }
    }

    private void requireAcceptable(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidSbomException("Datoteka je prazna.");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new InvalidSbomException(
                    "Datoteka je prevelika (najviše " + (maxSizeBytes / 1024 / 1024) + " MB).");
        }
    }

    /** Naziv produkta skracen na duljinu stupca (255); prazno -> null. */
    private String clip(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 255 ? trimmed.substring(0, 255) : trimmed;
    }

    /** Ime bez putanje i upravljackih znakova - isti razlog kao kod priloga. */
    private String safeFileName(String original) {
        String name = original == null ? "" : original.trim();
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (name.isEmpty()) {
            name = "sbom.json";
        }
        return name.length() > 200 ? name.substring(0, 200) : name;
    }
}
