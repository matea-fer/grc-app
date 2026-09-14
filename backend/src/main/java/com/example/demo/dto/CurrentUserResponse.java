package com.example.demo.dto;

import com.example.demo.model.Role;

/** Tko je prijavljen - odgovor na {@code GET /api/auth/me}, bez tokena. */
public record CurrentUserResponse(String username, Role role, Long companyId, String companyName) {
}
