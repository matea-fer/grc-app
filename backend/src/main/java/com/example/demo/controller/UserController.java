package com.example.demo.controller;

import com.example.demo.dto.CreateUserRequest;
import com.example.demo.dto.UserResponse;
import com.example.demo.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Korisnici - za globalnog administratora i za administratora firme.
 *
 * Ruta namjerno NIJE tenant-scoped (nema je u {@code TenantFilter}): globalni ADMIN
 * dodaje korisnike bilo kojoj firmi, pa firma za njega dolazi iz tijela zahtjeva.
 * Administratoru firme se tijelo ne vjeruje - njemu firmu odreduje token, a doseg
 * povlaci {@link UserService}.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    // GET /api/users - svi korisnici
    @GetMapping
    public List<UserResponse> getAll() {
        return service.getAll();
    }

    // POST /api/users - dodaj korisnika
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        return service.create(request);
    }

    // DELETE /api/users/1 - obriši korisnika
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
