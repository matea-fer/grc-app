package com.example.demo.controller;

import com.example.demo.dto.LogEntryResponse;
import com.example.demo.dto.PageResponse;
import com.example.demo.service.LogQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Dnevnik akcija firme iz konteksta. Samo citanje - zapisi nastaju iskljucivo kao
 * posljedica stvarnih akcija u servisima; nista ih ne smije stvarati ni mijenjati
 * izvana, inace prestaju biti dokaz o icemu.
 */
@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final LogQueryService service;

    public LogController(LogQueryService service) {
        this.service = service;
    }

    /**
     * GET /api/logs?page=0&from=2026-08-01&action=RECORD_LOCKED - stranica dnevnika,
     * najnovije prvo.
     *
     * {@code from} je datum OD kojeg se gleda, ukljucivo; bez njega se gleda sve. Datum je
     * lokalni ({@code yyyy-MM-dd}) i pretvara se u trenutak u nasoj zoni - vidi
     * {@code LogQueryService.startOf}. Neispravan datum zavrsava kao 400, kroz
     * {@code ApiExceptionHandler.handleTypeMismatch}.
     */
    @GetMapping
    public PageResponse<LogEntryResponse> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) String action) {
        return service.getForCurrentCompany(page, from, action);
    }
}
