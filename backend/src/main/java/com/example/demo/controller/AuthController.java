package com.example.demo.controller;

import com.example.demo.dto.CurrentUserResponse;
import com.example.demo.dto.LoginRequest;
import com.example.demo.dto.LoginResponse;
import com.example.demo.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prijava. {@code /api/auth/login} je jedina ruta pod {@code /api} koju
 * {@link com.example.demo.auth.JwtAuthFilter} propusta bez tokena - inace se ne bi
 * imalo kako prijaviti.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    // POST /api/auth/login - prijava, vraca token
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return service.login(request);
    }

    // GET /api/auth/me - tko je prijavljen (frontend provjerava vrijedi li jos token iz localStorage)
    @GetMapping("/me")
    public CurrentUserResponse me() {
        return service.me();
    }
}
