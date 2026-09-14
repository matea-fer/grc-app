package com.example.demo.dto;

import com.example.demo.model.CodebookScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Firma se NAMJERNO ne prima iz tijela. Kod TENANT dosega uzima se iz tenant konteksta
 * (token, odnosno TenantID zaglavlje za globalnog administratora), isto kao kod obrazaca
 * i zapisa - klijentu kojem bismo vjerovali mogao bi se podmetnuti tudi companyId.
 */
public record CreateCodebookRequest(
        @NotBlank String name,
        @NotNull CodebookScope scope
) {
}
