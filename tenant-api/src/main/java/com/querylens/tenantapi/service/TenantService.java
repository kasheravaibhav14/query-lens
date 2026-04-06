package com.querylens.tenantapi.service;

import com.google.common.hash.Hashing;
import com.querylens.tenantapi.dto.RegisterTenantRequest;
import com.querylens.tenantapi.dto.RegisterTenantResponse;
import com.querylens.tenantapi.dto.TenantResponse;
import com.querylens.tenantapi.dto.ValidateApiKeyRequest;
import com.querylens.tenantapi.kafka.KafkaTopicProvisioningService;
import com.querylens.tenantapiclient.model.Tenant;
import com.querylens.tenantapiclient.model.TenantStatus;
import com.querylens.tenantapiclient.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final KafkaTopicProvisioningService kafkaTopicProvisioningService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public TenantService(TenantRepository tenantRepository, KafkaTopicProvisioningService kafkaTopicProvisioningService) {
        this.tenantRepository = tenantRepository;
        this.kafkaTopicProvisioningService = kafkaTopicProvisioningService;
    }

    @Transactional
    public RegisterTenantResponse register(RegisterTenantRequest request) {
        if (tenantRepository.existsByName(request.name())) {
            throw new IllegalArgumentException("Tenant name already taken: " + request.name());
        }

        String rawApiKey = generateApiKey();
        String hashedApiKey = Hashing.sha256().hashString(rawApiKey, StandardCharsets.UTF_8).toString();

        Tenant tenant = new Tenant();
        tenant.setName(request.name());
        tenant.setDescription(request.description());
        tenant.setApiKeyHash(hashedApiKey);
        tenant.setDatabaseType(request.databaseType());

        tenant = tenantRepository.save(tenant);

        kafkaTopicProvisioningService.provisionTopicAsync(tenant.getId());

        return new RegisterTenantResponse(tenant.getId(), tenant.getName(), tenant.getDescription(), rawApiKey, tenant.getDatabaseType(), tenant.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public TenantResponse validateApiKey(ValidateApiKeyRequest request) {
        String hash = Hashing.sha256().hashString(request.apiKey(), StandardCharsets.UTF_8).toString();
        Tenant tenant = tenantRepository.findByApiKeyHash(hash).orElseThrow(() -> new IllegalArgumentException("Invalid API key"));
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new IllegalStateException("Tenant is suspended: " + tenant.getId());
        }
        return toResponse(tenant);
    }

    @Transactional(readOnly = true)
    public TenantResponse getById(UUID id) {
        Tenant tenant = tenantRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + id));
        return toResponse(tenant);
    }

    private String generateApiKey() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "ql_" + HexFormat.of().formatHex(bytes);
    }

    private TenantResponse toResponse(Tenant tenant) {
        return new TenantResponse(tenant.getId(), tenant.getName(), tenant.getDescription(), tenant.getStatus(), tenant.getDatabaseType(), tenant.getCreatedAt());
    }
}
