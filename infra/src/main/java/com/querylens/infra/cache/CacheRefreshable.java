package com.querylens.infra.cache;

public interface CacheRefreshable {

    LocalCacheType cacheType();

    void refreshAll();
}
