package com.querylens.tenantapiclient.cache;

import com.querylens.infra.model.DatabaseType;
import com.querylens.tenantapiclient.model.TenantStatus;

import java.util.UUID;

public record CachedTenant(
        UUID tenantId,
        String name,
        TenantStatus status,
        DatabaseType databaseType
) {}
