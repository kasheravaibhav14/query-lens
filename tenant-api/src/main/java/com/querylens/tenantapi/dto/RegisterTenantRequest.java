package com.querylens.tenantapi.dto;

import com.querylens.tenantapi.model.DatabaseType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterTenantRequest(
        @NotBlank(message = "Name is required")
        @Size(min = 3, max = 100, message = "Name must be between 3 and 100 characters")
        @Pattern(
                regexp = "^[a-zA-Z0-9][a-zA-Z0-9_-]*$",
                message = "Name must start with a letter or digit and contain only letters, digits, hyphens, or underscores"
        )
        String name,

        @Size(max = 500, message = "Description must not exceed 500 characters")
        String description,

        @NotNull(message = "Database type is required")
        DatabaseType databaseType
) {}
