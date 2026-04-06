package com.querylens.ingestion.controller;

import com.querylens.ingestion.service.IngestService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ingest")
public class IngestController {

    private final IngestService ingestService;

    @Autowired
    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping
    public ResponseEntity<Void> ingest(
            @RequestHeader("X-Api-Key") String apiKey,
            @RequestHeader(value = "X-Source", defaultValue = "") String source,
            @Valid @RequestBody IngestRequest request) {
        ingestService.ingest(apiKey, source, request.logs());
        return ResponseEntity.noContent().build();
    }
}
