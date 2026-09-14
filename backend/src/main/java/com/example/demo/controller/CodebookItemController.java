package com.example.demo.controller;

import com.example.demo.dto.CodebookItemResponse;
import com.example.demo.dto.SaveCodebookItemsRequest;
import com.example.demo.service.CodebookItemService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Stavke jednog sifrarnika. Sifrarnik dolazi iz putanje i mora biti u dosegu
 * pozivatelja (inace 404), pa se do tudih stavki ne moze doci.
 */
@RestController
@RequestMapping("/api/codebooks/{codebookId}/items")
public class CodebookItemController {

    private final CodebookItemService service;

    public CodebookItemController(CodebookItemService service) {
        this.service = service;
    }

    // GET /api/codebooks/1/items - stavke, poredane po sortOrder
    @GetMapping
    public List<CodebookItemResponse> getAll(@PathVariable Long codebookId) {
        return service.getForCodebook(codebookId);
    }

    // PUT /api/codebooks/1/items - spremi cijeli popis odjednom (puna zamjena + redoslijed)
    @PutMapping
    public List<CodebookItemResponse> save(@PathVariable Long codebookId,
                                           @Valid @RequestBody SaveCodebookItemsRequest request) {
        return service.save(codebookId, request);
    }
}
