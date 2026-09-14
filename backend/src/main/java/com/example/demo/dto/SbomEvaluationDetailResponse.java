package com.example.demo.dto;

import com.example.demo.model.SbomStatus;

import java.time.Instant;
import java.util.List;

/**
 * Puna slika jedne evaluacije: sazetak + popis ranjivosti. Vraca se na dohvat jedne
 * evaluacije (ekran s detaljima), ne u popisu.
 */
public record SbomEvaluationDetailResponse(
        Long id,
        String fileName,
        SbomStatus status,
        Instant uploadedAt,
        Instant finishedAt,
        String uploadedBy,
        String toolVersion,
        int total,
        int critical,
        int high,
        int medium,
        int low,
        int negligible,
        int unknown,
        String errorMessage,
        List<SbomVulnerability> vulnerabilities
) {
}
