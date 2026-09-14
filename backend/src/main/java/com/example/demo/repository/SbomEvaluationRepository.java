package com.example.demo.repository;

import com.example.demo.model.SbomEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SbomEvaluationRepository extends JpaRepository<SbomEvaluation, Long> {

    /** Evaluacije jedne firme, najnovije prvo. */
    List<SbomEvaluation> findByCompanyIdOrderByUploadedAtDescIdDesc(Long companyId);

    /**
     * Jedna evaluacija, ali samo ako pripada zadanoj firmi.
     *
     * Tudi ili nepostojeci id jednako zavrsavaju kao "nema" (404 u servisu) - ista
     * izolacija kao i svugdje: 404, ne 403, da se ne oda da tudi zapis postoji.
     */
    Optional<SbomEvaluation> findByIdAndCompanyId(Long id, Long companyId);
}
