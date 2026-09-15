package com.example.demo.dto;

import com.example.demo.model.Template;

public record TemplateResponse(Long id, String name, boolean questionnaire) {
    public static TemplateResponse from(Template template) {
        return new TemplateResponse(template.getId(), template.getName(), template.isQuestionnaire());
    }
}
