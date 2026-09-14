package com.example.demo.controller;

import com.example.demo.dto.CompanyResponse;
import com.example.demo.dto.CreateCompanyRequest;
import com.example.demo.service.CompanyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanyService service;

    public CompanyController(CompanyService service) {
        this.service = service;
    }

    // GET /api/companies - aktivne firme
    @GetMapping
    public List<CompanyResponse> getAll() {
        return service.getAll();
    }

    // GET /api/companies/archived - arhivirane firme (samo globalni ADMIN)
    @GetMapping("/archived")
    public List<CompanyResponse> getArchived() {
        return service.getArchived();
    }

    // POST /api/companies - dodaj firmu
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyResponse create(@Valid @RequestBody CreateCompanyRequest request) {
        return service.create(request);
    }

    // PUT /api/companies/1 - preimenuj firmu
    @PutMapping("/{id}")
    public CompanyResponse update(@PathVariable Long id, @Valid @RequestBody CreateCompanyRequest request) {
        return service.update(id, request);
    }

    // DELETE /api/companies/1 - arhiviraj firmu (podaci ostaju, vidi CompanyService)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    // POST /api/companies/1/restore - vrati firmu iz arhive
    @PostMapping("/{id}/restore")
    public CompanyResponse restore(@PathVariable Long id) {
        return service.restore(id);
    }

    /**
     * DELETE /api/companies/1/purge - trajno ukloni firmu i sve njezine podatke.
     *
     * Zasebna ruta, a ne npr. {@code DELETE ?force=true} na postojecoj: nepovratna radnja
     * ne smije se od povratne razlikovati samo po parametru koji se lako izostavi ili
     * doda. Ovako je jedini nacin da se do nje dode - traziti bas nju.
     */
    @DeleteMapping("/{id}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@PathVariable Long id) {
        service.purge(id);
    }
}
