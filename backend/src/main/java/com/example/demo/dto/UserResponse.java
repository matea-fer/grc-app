package com.example.demo.dto;

import com.example.demo.model.Role;
import com.example.demo.model.User;

/** Korisnik bez ijednog traga lozinke - hash ne izlazi iz backenda ni u kojem obliku. */
public record UserResponse(Long id, String username, Role role, Long companyId, String companyName) {
    public static UserResponse from(User user, String companyName) {
        return new UserResponse(user.getId(), user.getUsername(), user.getRole(), user.getCompanyId(), companyName);
    }
}
