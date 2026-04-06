package com.querylens.analysisengine.service;

import com.querylens.analysisengine.controller.AnalyzeResponse;
import com.querylens.analysisengine.rules.Finding;
import com.querylens.analysisengine.rules.RuleEngine;
import com.querylens.analysisengine.store.InMemoryWindowStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AnalyzeService {

    private final InMemoryWindowStore windowStore;
    private final RuleEngine ruleEngine;
    private final long windowDurationMillis;

    @Autowired
    public AnalyzeService(InMemoryWindowStore windowStore,
                          RuleEngine ruleEngine,
                          @Value("${query-lens.window.duration-millis:60000}") long windowDurationMillis) {
        this.windowStore = windowStore;
        this.ruleEngine = ruleEngine;
        this.windowDurationMillis = windowDurationMillis;
    }

    public AnalyzeResponse analyze(UUID tenantId) {
        List<Finding> findings = ruleEngine.evaluate(windowStore.getWindow(tenantId));
        return new AnalyzeResponse(tenantId, windowDurationMillis / 1000, Instant.now(), findings);
    }
}
