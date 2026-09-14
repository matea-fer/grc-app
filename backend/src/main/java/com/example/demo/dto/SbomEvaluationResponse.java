package com.example.demo.dto;

import com.example.demo.model.SbomEvaluation;
import com.example.demo.model.SbomStatus;

import java.time.Instant;

/**
 * Sazetak jedne SBOM evaluacije - za popis i za polling statusa. Bez samog popisa
 * ranjivosti (taj je u {@link SbomEvaluationDetailResponse}), jer popis evaluacija ga
 * ne treba, a zna biti velik.
 */
public record SbomEvaluationResponse(
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
        String errorMessage
) {
    public static SbomEvaluationResponse from(SbomEvaluation e) {
        return new SbomEvaluationResponse(
                e.getId(),
                e.getFileName(),
                e.getStatus(),
                e.getUploadedAt(),
                e.getFinishedAt(),
                e.getUploadedBy(),
                e.getToolVersion(),
                e.getTotalCount(),
                e.getCriticalCount(),
                e.getHighCount(),
                e.getMediumCount(),
                e.getLowCount(),
                e.getNegligibleCount(),
                e.getUnknownCount(),
                e.getErrorMessage()
        );
    }
}
