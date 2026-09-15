package com.example.demo.controller;

import com.example.demo.dto.CreateTemplateRequest;
import com.example.demo.dto.DeletePlanResponse;
import com.example.demo.dto.DeleteTemplatesRequest;
import com.example.demo.dto.PageResponse;
import com.example.demo.dto.RecordOptionResponse;
import com.example.demo.dto.TemplateResponse;
import com.example.demo.dto.UpdateTemplateRequest;
import com.example.demo.service.TemplateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Templatei (obrasci) firme iz tenant konteksta (TenantID). Firma se ne prima iz
 * URL-a ni tijela, pa jedna firma ne moze citati ni mijenjati tude templatee.
 */
@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService service;

    public TemplateController(TemplateService service) {
        this.service = service;
    }

    // GET /api/templates - templatei firme iz konteksta
    @GetMapping
    public List<TemplateResponse> getAll() {
        return service.list();
    }

    // GET /api/templates/1 - jedan obrazac (404 ako nije firme iz konteksta)
    @GetMapping("/{id}")
    public TemplateResponse getOne(@PathVariable Long id) {
        return service.getOne(id);
    }

    // POST /api/templates - novi template za firmu iz konteksta
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse create(@Valid @RequestBody CreateTemplateRequest request) {
        return service.create(request);
    }

    /**
     * GET /api/templates/1/options?displayKey=naziv&q=proc&page=0
     *
     * Zapisi ovog obrasca kao ponuda za vezu - samo id i naziv. Sluzi dijalogu za odabir u
     * referentnom stupcu drugog obrasca.
     *
     * Zasto zaseban put, a ne postojeci dohvat zapisa: ondje se vraca cijeli {@code data} i
     * podaci o zakljucanosti, a dijalogu treba dvoje. Pretraga je usto ILI (naziv ili id),
     * dok su filtri nad zapisima uvijek I.
     *
     * {@code ids} sluzi drugom pitanju - ne "sto mi se nudi" nego "kako se zovu ovi zapisi".
     * Tim putem tablica jednim pozivom razrijesi sve veze na svojoj stranici.
     */
    @GetMapping("/{id}/options")
    public PageResponse<RecordOptionResponse> options(@PathVariable Long id,
                                                      @RequestParam String displayKey,
                                                      @RequestParam(name = "q", required = false) String search,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(required = false) List<Long> ids) {
        return service.options(id, displayKey, search, page, ids);
    }

    // PUT /api/templates/1 - preimenuj template (404 ako nije firmin)
    @PutMapping("/{id}")
    public TemplateResponse rename(@PathVariable Long id, @Valid @RequestBody UpdateTemplateRequest request) {
        return service.rename(id, request);
    }

    // PUT /api/templates/1/questionnaire?value=true - oznaci/odznaci obrazac kao upitnik
    @PutMapping("/{id}/questionnaire")
    public TemplateResponse setQuestionnaire(@PathVariable Long id, @RequestParam boolean value) {
        return service.setQuestionnaire(id, value);
    }

    // DELETE /api/templates/1 - obriši template i sve njegove zapise
    /**
     * POST /api/templates/bulk-delete/preview - sto bi skupno brisanje odnijelo.
     *
     * Ne mijenja nista i vraca i prepreke, ako ih ima, pa se ekran ne mora pogadati sto ce
     * proci. Pojedinacno brisanje ostaje na {@code DELETE /api/templates/{id}} i nije dirano.
     */
    @PostMapping("/bulk-delete/preview")
    public DeletePlanResponse deletionPreview(@Valid @RequestBody DeleteTemplatesRequest request) {
        return service.deletionPlan(request.templateIds());
    }

    // POST /api/templates/bulk-delete - obrisi vise obrazaca odjednom
    @PostMapping("/bulk-delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void bulkDelete(@Valid @RequestBody DeleteTemplatesRequest request) {
        service.deleteAll(request.templateIds());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
