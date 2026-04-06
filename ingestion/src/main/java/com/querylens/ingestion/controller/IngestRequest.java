package com.querylens.ingestion.controller;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record IngestRequest(
        @NotEmpty(message = "logs must not be empty")
        List<String> logs
) {}
