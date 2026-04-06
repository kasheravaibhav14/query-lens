package com.querylens.tenantapiclient;

import com.querylens.tenantapiclient.cache.ApiKeyCacheService;
import com.querylens.tenantapiclient.repository.TenantRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@AutoConfigurationPackage
public class TenantApiClientAutoConfiguration {

    @Bean
    public ApiKeyCacheService apiKeyCacheService(TenantRepository tenantRepository) {
        return new ApiKeyCacheService(tenantRepository);
    }
}
