package com.querylens.tenantapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ValidateApiKeyRequest(
        @NotBlank(message = "API key is required")
        @Pattern(
                regexp = "^ql_[0-9a-f]{64}$",
                message = "Invalid API key format"
        )
        String apiKey
) {}
