package com.example.demo.dto;

import com.example.demo.model.Template;

public record TemplateResponse(Long id, String name) {
    public static TemplateResponse from(Template template) {
        return new TemplateResponse(template.getId(), template.getName());
    }
}
