package com.example.demo.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateTemplateRequest(
        @NotBlank String name
) {
}
