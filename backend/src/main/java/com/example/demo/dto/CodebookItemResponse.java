package com.example.demo.dto;

import com.example.demo.model.CodebookItem;

/**
 * Jedna stavka sifrarnika. {@code code} je stabilna sifra koja se sprema u podatke,
 * {@code name} je ono sto korisnik vidi.
 */
public record CodebookItemResponse(
        Long id,
        String code,
        String name,
        boolean active,
        int sortOrder
) {
    public static CodebookItemResponse from(CodebookItem item) {
        return new CodebookItemResponse(item.getId(), item.getCode(), item.getName(),
                item.isActive(), item.getSortOrder());
    }
}
