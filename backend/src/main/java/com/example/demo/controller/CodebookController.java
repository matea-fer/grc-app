package com.example.demo.controller;

import com.example.demo.dto.CodebookResponse;
import com.example.demo.dto.CreateCodebookRequest;
import com.example.demo.dto.UpdateCodebookRequest;
import com.example.demo.model.CodebookScope;
import com.example.demo.service.CodebookService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Sifrarnici. Ruta je "tenant-optional" (vidi TenantFilter): firma se koristi kad je
 * poznata, ali nije uvjet - globalni sifrarnici ne pripadaju nijednoj.
 *
 * Doseg i ovlasti odreduje {@link CodebookService}; ovdje se firma ne cita ni iz
 * putanje ni iz tijela.
 */
@RestController
@RequestMapping("/api/codebooks")
public class CodebookController {

    private final CodebookService service;

    public CodebookController(CodebookService service) {
        this.service = service;
    }

    // GET /api/codebooks?scope=TENANT&search=stat - dostupni šifrarnici, s brojem stavki
    @GetMapping
    public List<CodebookResponse> getAll(@RequestParam(required = false) CodebookScope scope,
                                         @RequestParam(required = false) String search) {
        return service.list(scope, search);
    }

    // GET /api/codebooks/1 - jedan šifrarnik (404 ako nije u dosegu)
    @GetMapping("/{id}")
    public CodebookResponse getOne(@PathVariable Long id) {
        return service.getOne(id);
    }

    // POST /api/codebooks - novi šifrarnik (firma iz konteksta, ne iz tijela)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CodebookResponse create(@Valid @RequestBody CreateCodebookRequest request) {
        return service.create(request);
    }

    // PUT /api/codebooks/1 - preimenuj (doseg se ne mijenja)
    @PutMapping("/{id}")
    public CodebookResponse rename(@PathVariable Long id, @Valid @RequestBody UpdateCodebookRequest request) {
        return service.rename(id, request);
    }

    // DELETE /api/codebooks/1 - obriši šifrarnik i sve njegove stavke
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
