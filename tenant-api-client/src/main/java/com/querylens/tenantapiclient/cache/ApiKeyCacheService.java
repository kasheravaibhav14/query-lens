package com.querylens.tenantapiclient.cache;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.hash.Hashing;
import com.querylens.infra.cache.CacheRefreshable;
import com.querylens.infra.cache.LocalCacheType;
import com.querylens.tenantapiclient.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class ApiKeyCacheService implements CacheRefreshable {

    private final LoadingCache<String, Optional<CachedTenant>> cache;

    @Autowired
    public ApiKeyCacheService(TenantRepository tenantRepository) {
        this.cache = CacheBuilder.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .build(CacheLoader.from(rawApiKey -> {
                    String hash = Hashing.sha256().hashString(rawApiKey, StandardCharsets.UTF_8).toString();
                    return tenantRepository.findByApiKeyHash(hash)
                            .map(TenantTransformer::toCached);
                }));
    }

    public Optional<CachedTenant> get(String rawApiKey) {
        return cache.getUnchecked(rawApiKey);
    }

    @Override
    public LocalCacheType cacheType() {
        return LocalCacheType.TENANT_API_KEYS;
    }

    @Override
    public void refreshAll() {
        cache.invalidateAll();
    }
}
