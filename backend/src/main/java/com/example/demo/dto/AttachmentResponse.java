package com.example.demo.dto;

import com.example.demo.model.Attachment;

import java.time.Instant;

/**
 * Prilog kakvim ga vidi frontend - sve osim sadrzaja.
 *
 * Sadrzaj se dohvaca zasebnim pozivom, i to tek kad ga korisnik zatrazi: popis priloga se
 * crta uz svaki redak tablice, pa bi datoteke u odgovoru znacile da se pri svakom otvaranju
 * ekrana prenese sve sto je ikad prilozeno.
 */
public record AttachmentResponse(
        Long id,
        Long surveyId,
        String columnKey,
        String fileName,
        String contentType,
        long sizeBytes,
        String uploadedBy,
        Instant uploadedAt
) {

    public static AttachmentResponse from(Attachment attachment) {
        return new AttachmentResponse(attachment.getId(), attachment.getSurveyId(), attachment.getColumnKey(),
                attachment.getFileName(), attachment.getContentType(), attachment.getSizeBytes(),
                attachment.getUploadedBy(), attachment.getUploadedAt());
    }
}
