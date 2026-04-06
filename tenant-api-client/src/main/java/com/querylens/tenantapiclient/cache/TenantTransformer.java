package com.querylens.tenantapiclient.cache;

import com.querylens.tenantapiclient.model.Tenant;

public final class TenantTransformer {

    private TenantTransformer() {}

    public static CachedTenant toCached(Tenant tenant) {
        return new CachedTenant(
                tenant.getId(),
                tenant.getName(),
                tenant.getStatus(),
                tenant.getDatabaseType()
        );
    }
}
