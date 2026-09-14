package com.example.demo.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Cijeli popis stavki odjednom - puna zamjena, ne spajanje, isto kao PUT nad zapisom.
 * Sto god se posalje postaje cijeli sadrzaj sifrarnika: stavka koje u popisu nema se
 * brise.
 *
 * @param items poredani popis; {@code sortOrder} se NE salje jer ga server dodjeljuje
 *              po polozaju u nizu - klijent salje redoslijed, ne brojeve
 */
public record SaveCodebookItemsRequest(
        @NotNull @Valid List<CodebookItemInput> items
) {

    /**
     * @param id postojeca stavka koja se mijenja, ili {@code null} za novu. Identitet ide
     *           preko id-a, a ne preko sifre, da se sifra smije ispraviti bez da stavka
     *           izgubi identitet (i time povijest).
     */
    public record CodebookItemInput(
            Long id,
            @NotBlank String code,
            @NotBlank String name,
            Boolean active
    ) {

        /**
         * {@code Boolean}, ne primitivni {@code boolean}: na Jacksonu 3 izostavljeno
         * primitivno polje rusi zahtjev jos pri rasclanjivanju JSON-a (500 umjesto 400).
         *
         * Izostavljena zastavica ovdje znaci {@code true}, za razliku od zastavica na
         * stupcu - stavka je aktivna dok se izricito ne kaze suprotno, isto kao zadana
         * vrijednost u {@link com.example.demo.model.CodebookItem}.
         */
        public CodebookItemInput {
            active = active == null || active;
        }
    }
}
