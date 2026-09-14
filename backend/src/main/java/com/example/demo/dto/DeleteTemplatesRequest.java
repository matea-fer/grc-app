package com.example.demo.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Obrasci koji se brisu odjednom.
 *
 * Ide kao tijelo POST-a, a ne kao {@code DELETE} s popisom u adresi: popis zna biti dug, a
 * radnja ionako ima svoj korak najave prije izvrsenja.
 */
public record DeleteTemplatesRequest(@NotEmpty List<Long> templateIds) {
}
