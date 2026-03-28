package com.querylens.tenantapi.repository;

import com.querylens.tenantapi.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findByApiKeyHash(String apiKeyHash);

    boolean existsByName(String name);
}
