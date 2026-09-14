package com.example.demo.dto;

import java.util.Map;

// Kao i kod stvaranja, firma ne dolazi iz tijela nego iz tenant konteksta.
public record UpdateSurveyRequest(
        Map<String, Object> data
) {
}
