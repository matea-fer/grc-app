package com.example.demo.dto;

import com.example.demo.model.Role;

/**
 * Odgovor na uspjesnu prijavu. Uz token nosi i tko je prijavljen, da frontend ne
 * mora sam raspakiravati JWT - sadrzaj tokena bez provjere potpisa ionako nije
 * dokaz o icemu, pa je bolje da te podatke dobije od backenda.
 *
 * @param companyName naziv firme radi ispisa u traci; null za ADMIN-a, koji firmu bira
 */
public record LoginResponse(String token, String username, Role role, Long companyId, String companyName) {
}
