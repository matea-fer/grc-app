package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Firma za koju se template stvara NE dolazi iz tijela - uzima se iz tenant
 * konteksta (zaglavlja), pa jedna firma nikad ne stvara template drugoj.
 */
public record CreateTemplateRequest(
        @NotBlank String name
) {
}
