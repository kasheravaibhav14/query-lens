package com.querylens.analysisengine.controller;

import com.querylens.analysisengine.rules.Finding;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AnalyzeResponse(
        UUID tenantId,
        long windowSeconds,
        Instant asOf,
        List<Finding> findings
) {}
