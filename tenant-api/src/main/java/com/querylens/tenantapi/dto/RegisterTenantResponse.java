package com.querylens.tenantapi.dto;

import com.querylens.infra.model.DatabaseType;

import java.time.LocalDateTime;
import java.util.UUID;

public record RegisterTenantResponse(
        UUID id,
        String name,
        String description,
        String apiKey,  // plain key — shown once, never stored
        DatabaseType databaseType,
        LocalDateTime createdAt
) {}
