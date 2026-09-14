package com.example.demo.controller;

import com.example.demo.dto.CreateSurveyRequest;
import com.example.demo.dto.PageResponse;
import com.example.demo.dto.RecordChangeResponse;
import com.example.demo.dto.RelatedGroupResponse;
import com.example.demo.dto.SurveyResponse;
import com.example.demo.dto.UpdateSurveyRequest;
import com.example.demo.service.SurveyResultService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Zapisi jednog templatea. Template dolazi iz putanje i mora pripadati firmi iz
 * konteksta (inace 404), pa jedna firma ne vidi ni ne mijenja tude zapise.
 */
@RestController
@RequestMapping("/api/templates/{templateId}/surveys")
public class SurveyResultController {

    private final SurveyResultService service;

    public SurveyResultController(SurveyResultService service) {
        this.service = service;
    }

    /**
     * GET /api/templates/1/surveys?page=0&sortBy=grad&sortDir=asc&filter=grad:zagreb
     *
     * Jedna stranica zapisa. Velicine stranice nema medu parametrima namjerno - fiksna je
     * ({@link com.example.demo.service.SurveyResultService#PAGE_SIZE}), pa se ne moze
     * zatraziti "sve odjednom" i time ponistiti smisao stranicenja.
     *
     * {@code filter} se smije ponoviti; uvjeti se spajaju s I. Vrijednost smije sadrzavati
     * dvotocku (dijeli se samo na prvoj), pa "vrijeme:12:30" trazi "12:30".
     *
     * {@code ids} suzava dohvat na tocno zadane zapise. To NIJE filtar nego druga vrsta pitanja:
     * filtri govore o sadrzaju jsonb-a, a ovo o samim retcima. Koristi ga dijalog povezanih
     * zapisa u smjeru naprijed, gdje su id-evi vec poznati iz podataka retka.
     */
    @GetMapping
    public PageResponse<SurveyResponse> getAll(@PathVariable Long templateId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(required = false) String sortBy,
                                               @RequestParam(required = false) String sortDir,
                                               @RequestParam(name = "filter", required = false) List<String> filter,
                                               @RequestParam(required = false) List<Long> ids) {
        return service.getAll(templateId, page, sortBy, sortDir, filter, ids);
    }

    // GET /api/templates/1/surveys/5 - jedan zapis (404 ako nije tog templatea)
    @GetMapping("/{id}")
    public SurveyResponse getOne(@PathVariable Long templateId, @PathVariable Long id) {
        return service.getOne(templateId, id);
    }

    /**
     * GET /api/templates/1/surveys/5/history - revizijski trag jednog zapisa.
     *
     * Pod zapisom, a ne kao zaseban resurs: povijest bez svog zapisa nema smisla, a ovako
     * kroz istu putanju prolazi i ista provjera pripadnosti firmi.
     */
    @GetMapping("/{id}/history")
    public List<RecordChangeResponse> history(@PathVariable Long templateId, @PathVariable Long id) {
        return service.history(templateId, id);
    }

    /**
     * GET /api/templates/1/surveys/5/related - tko sve pokazuje na ovaj zapis.
     *
     * Vraca SKUPINE (obrazac + stupac + koliko ih je), ne same zapise. Zapisi se dohvacaju tek
     * kad se skupina odabere, obicnim straničenim dohvatom uz filtar po vezi - istim putem
     * kojim korisnik i rucno filtrira.
     *
     * Nista se ne prima uz putanju: koji stupci na ovaj obrazac pokazuju vec pise u shemama.
     */
    @GetMapping("/{id}/related")
    public List<RelatedGroupResponse> related(@PathVariable Long templateId, @PathVariable Long id) {
        return service.related(templateId, id);
    }

    // POST /api/templates/1/surveys - dodaj zapis
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SurveyResponse create(@PathVariable Long templateId, @Valid @RequestBody CreateSurveyRequest request) {
        return service.create(templateId, request);
    }

    // PUT /api/templates/1/surveys/5 - uredi zapis (puna zamjena)
    @PutMapping("/{id}")
    public SurveyResponse update(@PathVariable Long templateId, @PathVariable Long id,
                                 @Valid @RequestBody UpdateSurveyRequest request) {
        return service.update(templateId, id, request);
    }

    /**
     * POST /api/templates/1/surveys/5/lock - zakljucaj zapis.
     *
     * Zakljucanost je stanje zapisa, pa je i ruta podresurs zapisa, a ne polje u tijelu
     * PUT-a: PUT je puna zamjena podataka i njega zakljucan zapis vise ne prima. Zakljucava
     * svaki prijavljeni korisnik ("gotov sam"), otkljucava samo administrator.
     */
    @PostMapping("/{id}/lock")
    public SurveyResponse lock(@PathVariable Long templateId, @PathVariable Long id) {
        return service.lock(templateId, id);
    }

    // DELETE /api/templates/1/surveys/5/lock - otključaj zapis (samo administrator)
    @DeleteMapping("/{id}/lock")
    public SurveyResponse unlock(@PathVariable Long templateId, @PathVariable Long id) {
        return service.unlock(templateId, id);
    }

    // DELETE /api/templates/1/surveys/5 - obriši zapis
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long templateId, @PathVariable Long id) {
        service.delete(templateId, id);
    }
}
