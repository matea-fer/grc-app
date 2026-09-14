package com.example.demo.controller;

import com.example.demo.dto.ColumnDefinitionResponse;
import com.example.demo.dto.CreateColumnDefinitionRequest;
import com.example.demo.dto.ReorderColumnsRequest;
import com.example.demo.dto.UpdateColumnDefinitionRequest;
import com.example.demo.service.ColumnDefinitionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Stupci jednog templatea. Template dolazi iz putanje, a mora pripadati firmi iz
 * tenant konteksta - inace servis vraca 404, pa jedna firma ne moze citati ni
 * mijenjati shemu tudeg templatea.
 */
@RestController
@RequestMapping("/api/templates/{templateId}/columns")
public class ColumnDefinitionController {

    private final ColumnDefinitionService service;

    public ColumnDefinitionController(ColumnDefinitionService service) {
        this.service = service;
    }

    // GET /api/templates/1/columns - stupci templatea
    @GetMapping
    public List<ColumnDefinitionResponse> getForTemplate(@PathVariable Long templateId) {
        return service.getForTemplate(templateId);
    }

    // POST /api/templates/1/columns - deklariraj stupac (409 ako naziv vec postoji u templateu)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ColumnDefinitionResponse create(@PathVariable Long templateId,
                                           @Valid @RequestBody CreateColumnDefinitionRequest request) {
        return service.create(templateId, request);
    }

    // PUT /api/templates/1/columns/grad - uredi stupac (preimenuj/promijeni tip); migrira zapise
    @PutMapping("/{key}")
    public ColumnDefinitionResponse update(@PathVariable Long templateId,
                                           @PathVariable String key,
                                           @Valid @RequestBody UpdateColumnDefinitionRequest request) {
        return service.update(templateId, key, request);
    }

    // PUT /api/templates/1/columns/order - nov redoslijed stupaca (samo prikaz, zapisi se ne diraju)
    //
    // Zasebna putanja ispod /columns, a ne PUT na /columns: to bi znacilo "zamijeni cijelu
    // shemu", pa bi izostavljen stupac bio brisanje stupca. Ovdje se salju samo kljucevi.
    @PutMapping("/order")
    public List<ColumnDefinitionResponse> reorder(@PathVariable Long templateId,
                                                  @Valid @RequestBody ReorderColumnsRequest request) {
        return service.reorder(templateId, request);
    }

    // DELETE /api/templates/1/columns/grad - makni stupac i pobrisi njegove vrijednosti
    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long templateId, @PathVariable String key) {
        service.delete(templateId, key);
    }
}
