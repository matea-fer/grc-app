package com.example.demo.controller;

import com.example.demo.dto.SbomEvaluationDetailResponse;
import com.example.demo.dto.SbomEvaluationResponse;
import com.example.demo.service.SbomService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * SBOM evaluacije firme iz tenant konteksta (TenantID). Analiza tece u pozadini,
 * pa upload vraca 202 s zapisom u stanju PENDING, a klijent polla {@code GET /{id}}
 * dok status ne postane DONE ili FAILED.
 *
 * Ruta je tenant-scoped (vidi {@code TenantFilter}); firma se ne prima iz tijela.
 */
@RestController
@RequestMapping("/api/sbom")
public class SbomController {

    private final SbomService service;

    public SbomController(SbomService service) {
        this.service = service;
    }

    // POST /api/sbom (multipart, polje "file") - pokrece analizu, vraca PENDING zapis.
    // productId/productName su neobavezni: kad se ekran otvori iz zapisa produkta, evaluacija
    // se vezuje bas uz taj produkt.
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SbomEvaluationResponse upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "productId", required = false) Long productId,
                                         @RequestParam(value = "productName", required = false) String productName) {
        return service.upload(file, productId, productName);
    }

    // GET /api/sbom - popis evaluacija firme (ili samo jednog produkta uz ?productId=...),
    // najnovije prvo (za polling statusa)
    @GetMapping
    public List<SbomEvaluationResponse> list(@RequestParam(value = "productId", required = false) Long productId) {
        return service.list(productId);
    }

    // GET /api/sbom/5 - jedna evaluacija s popisom ranjivosti (404 ako je tuđa)
    @GetMapping("/{id}")
    public SbomEvaluationDetailResponse detail(@PathVariable Long id) {
        return service.detail(id);
    }

    // DELETE /api/sbom/5 - obriši evaluaciju
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
