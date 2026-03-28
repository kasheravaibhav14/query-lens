package com.querylens.tenantapi.dto;

import com.querylens.tenantapi.model.DatabaseType;
import com.querylens.tenantapi.model.TenantStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record TenantResponse(
        UUID id,
        String name,
        String description,
        TenantStatus status,
        DatabaseType databaseType,
        LocalDateTime createdAt
) {}
