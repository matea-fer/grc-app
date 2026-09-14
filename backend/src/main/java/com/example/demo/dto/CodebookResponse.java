package com.example.demo.dto;

import com.example.demo.model.Codebook;
import com.example.demo.model.CodebookScope;

/**
 * Zaglavlje sifrarnika kakvim ga vidi frontend, s izracunatim brojem stavki.
 *
 * {@code itemCount} nije polje entiteta nego rezultat zasebnog upita koji broji stavke
 * za cijeli popis odjednom - da popis od N sifrarnika ne postane N upita.
 *
 * @param companyId firma kojoj sifrarnik pripada, ili null za GLOBAL doseg
 */
public record CodebookResponse(
        Long id,
        String name,
        CodebookScope scope,
        Long companyId,
        long itemCount
) {
    public static CodebookResponse from(Codebook codebook, long itemCount) {
        return new CodebookResponse(codebook.getId(), codebook.getName(), codebook.getScope(),
                codebook.getCompanyId(), itemCount);
    }
}
