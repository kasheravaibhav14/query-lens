package com.querylens.analysisengine.controller;

import com.querylens.analysisengine.service.AnalyzeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/analyze")
public class AnalyzeController {

    private final AnalyzeService analyzeService;

    @Autowired
    public AnalyzeController(AnalyzeService analyzeService) {
        this.analyzeService = analyzeService;
    }

    @GetMapping
    public ResponseEntity<AnalyzeResponse> analyze(@RequestParam UUID tenantId) {
        return ResponseEntity.ok(analyzeService.analyze(tenantId));
    }
}
