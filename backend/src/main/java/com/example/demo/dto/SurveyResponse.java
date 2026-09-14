package com.example.demo.dto;

import com.example.demo.model.SurveyResult;

import java.time.Instant;
import java.util.Map;

/**
 * Zapis kakav vidi klijent.
 *
 * Uz podatke nosi i zakljucanost: bez nje bi tablica morala pogadati zasto joj je izmjena
 * odbijena tek nakon sto je korisnik ispunio formu. {@code locked} je izveden iz
 * {@code lockedAt} i stoji uz njega namjerno - ekrani pitaju "smije li se mijenjati", a ne
 * "kada je zakljucan".
 */
public record SurveyResponse(
        Long id,
        Long companyId,
        Long templateId,
        Map<String, Object> data,
        boolean locked,
        Instant lockedAt,
        String lockedBy
) {
    public static SurveyResponse from(SurveyResult surveyResult) {
        return new SurveyResponse(
                surveyResult.getId(),
                surveyResult.getCompanyId(),
                surveyResult.getTemplateId(),
                surveyResult.getData(),
                surveyResult.isLocked(),
                surveyResult.getLockedAt(),
                surveyResult.getLockedBy()
        );
    }
}
