package com.querylens.tenantapi.service;

import com.querylens.tenantapi.dto.RegisterTenantRequest;
import com.querylens.tenantapi.dto.RegisterTenantResponse;
import com.querylens.tenantapi.dto.TenantResponse;
import com.querylens.tenantapi.dto.ValidateApiKeyRequest;
import com.querylens.tenantapi.model.TenantStatus;
import com.querylens.tenantapi.model.Tenant;
import com.querylens.tenantapi.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository tenantRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public TenantService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public RegisterTenantResponse register(RegisterTenantRequest request) {
        if (tenantRepository.existsByName(request.name())) {
            throw new IllegalArgumentException("Tenant name already taken: " + request.name());
        }

        String rawApiKey = generateApiKey();
        String hashedApiKey = sha256(rawApiKey);

        Tenant tenant = new Tenant();
        tenant.setName(request.name());
        tenant.setDescription(request.description());
        tenant.setApiKeyHash(hashedApiKey);
        tenant.setDatabaseType(request.databaseType());

        tenant = tenantRepository.save(tenant);

        return new RegisterTenantResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getDescription(),
                rawApiKey,  // returned once — caller must store it
                tenant.getDatabaseType(),
                tenant.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public TenantResponse validateApiKey(ValidateApiKeyRequest request) {
        String hash = sha256(request.apiKey());
        Tenant tenant = tenantRepository.findByApiKeyHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid API key"));
        if (tenant.getStatus() != TenantStatus.ACTIVE) {
            throw new IllegalStateException("Tenant is suspended: " + tenant.getId());
        }
        return toResponse(tenant);
    }

    @Transactional(readOnly = true)
    public TenantResponse getById(UUID id) {
        Tenant tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + id));
        return toResponse(tenant);
    }

    private String generateApiKey() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "ql_" + HexFormat.of().formatHex(bytes);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private TenantResponse toResponse(Tenant tenant) {
        return new TenantResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getDescription(),
                tenant.getStatus(),
                tenant.getDatabaseType(),
                tenant.getCreatedAt()
        );
    }
}
