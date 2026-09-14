package com.example.demo.dto;

import com.example.demo.model.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param companyId obavezan za USER i TENANT_ADMIN, mora izostati za globalnog
 *                  ADMIN-a. Kad zahtjev salje administrator firme, polje se i ne
 *                  gleda - firma mu dolazi iz tokena. Sve to provjerava
 *                  {@link com.example.demo.service.UserService}, jer pravilo ovisi
 *                  o odnosu vise polja i o ulozi posiljatelja, a ne o jednom polju
 */
public record CreateUserRequest(
        @NotBlank String username,
        @NotBlank @Size(min = 8, message = "mora imati najmanje 8 znakova") String password,
        @NotNull Role role,
        Long companyId
) {
}
