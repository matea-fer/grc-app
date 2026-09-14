package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Mijenja se samo naziv - doseg ne.
 *
 * Prebacivanje GLOBAL {@literal <->} TENANT bi znacilo da sifrarnik odjednom pripada
 * nekom drugom (ili nikome), a obrasci koji na njega pokazuju za to ne bi znali.
 * Tko treba drugi doseg, neka napravi novi sifrarnik.
 */
public record UpdateCodebookRequest(
        @NotBlank String name
) {
}
