package com.example.demo.dto;

import com.example.demo.model.LogEntry;

import java.time.Instant;

/**
 * Jedan redak dnevnika. {@code companyId} se ne vraca - popis je ionako vec
 * ogranicen na firmu iz konteksta, pa bi bio isti u svakom retku.
 *
 * @param username tko je akciju izvrsio; null za zapise nastale prije uvodenja prijave
 */
public record LogEntryResponse(Long id, String username, String action, String detail, Instant createdAt) {
    public static LogEntryResponse from(LogEntry entry) {
        return new LogEntryResponse(entry.getId(), entry.getUsername(), entry.getAction(),
                entry.getDetail(), entry.getCreatedAt());
    }
}
