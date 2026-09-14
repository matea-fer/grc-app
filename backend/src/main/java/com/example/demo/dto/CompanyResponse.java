package com.example.demo.dto;

import com.example.demo.model.Company;

import java.time.Instant;

/**
 * Firma prema pregledniku.
 *
 * {@code deletedAt} je null za aktivne firme, pa ga popis aktivnih ne mora ni gledati;
 * popisu arhiviranih sluzi za prikaz "kada je arhivirana".
 */
public record CompanyResponse(Long id, String name, Instant deletedAt) {
    public static CompanyResponse from(Company company) {
        return new CompanyResponse(company.getId(), company.getName(), company.getDeletedAt());
    }
}
