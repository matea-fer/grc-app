package com.example.demo.controller;

import com.example.demo.dto.TemplateResponse;
import com.example.demo.dto.TransferPlanResponse;
import com.example.demo.dto.TransferTemplatesRequest;
import com.example.demo.service.TemplateTransferService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Prijenos obrazaca izmedu firmi.
 *
 * Putanja je namjerno IZVAN {@code /api/templates/...}: ondje sve visi o obrascu jedne firme
 * iz konteksta, a ova radnja radi nad dvjema firmama odjednom i pripada administraciji, ne
 * pojedinom obrascu.
 *
 * Dva koraka, ne jedan. {@code /preview} nista ne mijenja i vraca isti izvjestaj koji ce
 * vratiti i sam prijenos - da se prije potvrde vidi koji sifrarnik nastaje, koji se obrazac
 * preimenuje i koja veza ostaje bez cilja.
 */
@RestController
@RequestMapping("/api/template-transfer")
public class TemplateTransferController {

    private final TemplateTransferService service;

    public TemplateTransferController(TemplateTransferService service) {
        this.service = service;
    }

    // GET /api/template-transfer/templates?companyId=1 - obrasci firme, za popis s kvacicama
    @GetMapping("/templates")
    public List<TemplateResponse> templatesOf(@RequestParam Long companyId) {
        return service.listFor(companyId);
    }

    // POST /api/template-transfer/preview - sto bi se dogodilo; ne mijenja nista
    @PostMapping("/preview")
    public TransferPlanResponse preview(@Valid @RequestBody TransferTemplatesRequest request) {
        return service.preview(request);
    }

    // POST /api/template-transfer - izvedi prijenos
    @PostMapping
    public TransferPlanResponse transfer(@Valid @RequestBody TransferTemplatesRequest request) {
        return service.transfer(request);
    }
}
