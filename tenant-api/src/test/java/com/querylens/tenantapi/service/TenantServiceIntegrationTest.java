package com.querylens.tenantapi.service;

import com.querylens.tenantapi.AbstractIntegrationTest;
import com.querylens.tenantapi.dto.RegisterTenantRequest;
import com.querylens.tenantapi.dto.RegisterTenantResponse;
import com.querylens.tenantapi.dto.TenantResponse;
import com.querylens.tenantapi.dto.ValidateApiKeyRequest;
import com.querylens.tenantapi.model.DatabaseType;
import com.querylens.tenantapi.model.Tenant;
import com.querylens.tenantapi.model.TenantStatus;
import com.querylens.tenantapi.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.*;

class TenantServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired TenantService tenantService;
    @Autowired TenantRepository tenantRepository;

    @BeforeEach
    void cleanup() {
        tenantRepository.deleteAll();
    }

    @Test
    void register_createsActiveTenantAndReturnsApiKeyOnce() {
        RegisterTenantRequest request = new RegisterTenantRequest("acme-corp", "Test tenant", DatabaseType.POSTGRES);

        RegisterTenantResponse response = tenantService.register(request);

        assertThat(response.id()).isNotNull();
        assertThat(response.name()).isEqualTo("acme-corp");
        assertThat(response.apiKey()).startsWith("ql_");
        assertThat(response.apiKey()).hasSize(67); // "ql_" + 64 hex chars
        assertThat(response.databaseType()).isEqualTo(DatabaseType.POSTGRES);
        assertThat(response.createdAt()).isNotNull();

        // key hash is stored, not the raw key
        Tenant persisted = tenantRepository.findById(response.id()).orElseThrow();
        assertThat(persisted.getApiKeyHash()).isNotEqualTo(response.apiKey());
        assertThat(persisted.getStatus()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void register_throwsOnDuplicateName() {
        tenantService.register(new RegisterTenantRequest("duplicate", null, DatabaseType.POSTGRES));

        assertThatThrownBy(() -> tenantService.register(new RegisterTenantRequest("duplicate", null, DatabaseType.POSTGRES)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already taken");
    }

    @Test
    void validateApiKey_returnsActiveTenantForValidKey() {
        RegisterTenantResponse registered = tenantService.register(new RegisterTenantRequest("my-service", null, DatabaseType.POSTGRES));

        TenantResponse result = tenantService.validateApiKey(new ValidateApiKeyRequest(registered.apiKey()));

        assertThat(result.id()).isEqualTo(registered.id());
        assertThat(result.name()).isEqualTo("my-service");
        assertThat(result.status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void validateApiKey_throwsForUnknownKey() {
        // valid format, but not registered
        String unknownKey = "ql_" + "a".repeat(64);

        assertThatThrownBy(() -> tenantService.validateApiKey(new ValidateApiKeyRequest(unknownKey)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid API key");
    }

    @Test
    void validateApiKey_throwsForSuspendedTenant() {
        RegisterTenantResponse registered = tenantService.register(new RegisterTenantRequest("suspended-svc", null, DatabaseType.POSTGRES));
        Tenant tenant = tenantRepository.findById(registered.id()).orElseThrow();
        tenant.setStatus(TenantStatus.SUSPENDED);
        tenantRepository.save(tenant);

        assertThatThrownBy(() -> tenantService.validateApiKey(new ValidateApiKeyRequest(registered.apiKey())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("suspended");
    }

    @Test
    void getById_returnsTenant() {
        RegisterTenantResponse registered = tenantService.register(new RegisterTenantRequest("get-me", "desc", DatabaseType.POSTGRES));

        TenantResponse result = tenantService.getById(registered.id());

        assertThat(result.id()).isEqualTo(registered.id());
        assertThat(result.description()).isEqualTo("desc");
    }

    @Test
    void getById_throwsForUnknownId() {
        assertThatThrownBy(() -> tenantService.getById(java.util.UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tenant not found");
    }
}
