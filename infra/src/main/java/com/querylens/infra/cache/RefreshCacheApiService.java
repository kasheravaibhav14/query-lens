package com.querylens.infra.cache;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// TODO: cross-service cache broadcast
// Each Spring Boot module (tenant-api, ingestion) maintains its own local cache.
// When a tenant is updated, this service only refreshes caches in the current process.
// Future: fan-out via Kafka event on a system.cache-refresh topic so all running
// instances across all services are invalidated in one broadcast.
public class RefreshCacheApiService {

    private final Map<LocalCacheType, CacheRefreshable> cacheMap;

    public RefreshCacheApiService(List<CacheRefreshable> impls) {
        this.cacheMap = impls.stream()
                .collect(Collectors.toMap(CacheRefreshable::cacheType, c -> c));
    }

    public void refresh(LocalCacheType type) {
        CacheRefreshable cache = cacheMap.get(type);
        if (cache != null) {
            cache.refreshAll();
        }
    }
}
