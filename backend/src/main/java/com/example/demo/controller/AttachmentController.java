package com.example.demo.controller;

import com.example.demo.dto.AttachmentResponse;
import com.example.demo.service.AttachmentService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Datoteke prilozene uz zapise jednog obrasca.
 *
 * Obrazac dolazi iz putanje i mora pripadati firmi iz konteksta (inace 404), pa jedna firma
 * ne vidi ni ne mijenja tude priloge - kao ni tude zapise.
 */
@RestController
@RequestMapping("/api/templates/{templateId}")
public class AttachmentController {

    private final AttachmentService service;

    public AttachmentController(AttachmentService service) {
        this.service = service;
    }

    // GET /api/templates/1/attachments - svi prilozi obrasca (tablica ih treba odjednom)
    @GetMapping("/attachments")
    public List<AttachmentResponse> listForTemplate(@PathVariable Long templateId) {
        return service.listForTemplate(templateId);
    }

    // GET /api/templates/1/surveys/5/attachments - prilozi jednog zapisa
    @GetMapping("/surveys/{surveyId}/attachments")
    public List<AttachmentResponse> listForSurvey(@PathVariable Long templateId, @PathVariable Long surveyId) {
        return service.listForSurvey(templateId, surveyId);
    }

    // POST /api/templates/1/surveys/5/attachments/dokument - priloz datoteku na stupac
    @PostMapping("/surveys/{surveyId}/attachments/{columnKey}")
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(@PathVariable Long templateId,
                                     @PathVariable Long surveyId,
                                     @PathVariable String columnKey,
                                     @RequestParam("file") MultipartFile file) {
        return service.upload(templateId, surveyId, columnKey, file);
    }

    /**
     * GET /api/templates/1/attachments/9/content - preuzimanje.
     *
     * Salje se UVIJEK kao {@code application/octet-stream} i uvijek kao privitak, bez obzira
     * na to sto je preglednik pri slanju javio. Razlog nije urednost: prilozena HTML ili SVG
     * datoteka posluzena sa svojom vrstom izvrsila bi se u pregledniku pod ISTOM adresom kao
     * aplikacija - i time dobila pristup tudoj prijavi. Spremljena vrsta zato sluzi samo za
     * prikaz u popisu.
     *
     * {@code nosniff} je druga brava: bez njega bi preglednik smio sam pogoditi vrstu i
     * ponisti ti prvu.
     */
    @GetMapping("/attachments/{attachmentId}/content")
    public ResponseEntity<Resource> download(@PathVariable Long templateId, @PathVariable Long attachmentId) {
        AttachmentService.Download download = service.download(templateId, attachmentId);

        // ime ide i kao ASCII (stariji klijenti) i kao UTF-8 - inace "Ponuda č.pdf" stigne izlomljena
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(download.attachment().getFileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(download.data().length)
                .body(new ByteArrayResource(download.data()));
    }

    // DELETE /api/templates/1/attachments/9 - makni prilog
    @DeleteMapping("/attachments/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long templateId, @PathVariable Long attachmentId) {
        service.delete(templateId, attachmentId);
    }
}
