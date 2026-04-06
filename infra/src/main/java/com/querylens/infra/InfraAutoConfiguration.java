package com.querylens.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.querylens.infra.async.QueryLensExecutorService;
import com.querylens.infra.cache.CacheRefreshController;
import com.querylens.infra.cache.CacheRefreshable;
import com.querylens.infra.cache.RefreshCacheApiService;
import com.querylens.infra.codec.EventCodec;
import com.querylens.infra.codec.JsonEventCodec;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;

@AutoConfiguration
public class InfraAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    public QueryLensExecutorService queryLensExecutorService() {
        return new QueryLensExecutorService();
    }

    @Bean
    public RefreshCacheApiService refreshCacheApiService(List<CacheRefreshable> impls) {
        return new RefreshCacheApiService(impls);
    }

    @Bean
    public CacheRefreshController cacheRefreshController(RefreshCacheApiService refreshCacheApiService) {
        return new CacheRefreshController(refreshCacheApiService);
    }

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Bean
    @Primary
    public EventCodec jsonEventCodec(ObjectMapper objectMapper) {
        return new JsonEventCodec(objectMapper);
    }
}
