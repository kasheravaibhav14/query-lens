package com.querylens.tenantapi.controller;

import com.querylens.tenantapi.dto.RegisterTenantRequest;
import com.querylens.tenantapi.dto.RegisterTenantResponse;
import com.querylens.tenantapi.dto.TenantResponse;
import com.querylens.tenantapi.dto.ValidateApiKeyRequest;
import com.querylens.tenantapi.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService tenantService;

    @Autowired
    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterTenantResponse> register(@Valid @RequestBody RegisterTenantRequest request) {
        RegisterTenantResponse response = tenantService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/validate-key")
    public ResponseEntity<TenantResponse> validateApiKey(@Valid @RequestBody ValidateApiKeyRequest request) {
        return ResponseEntity.ok(tenantService.validateApiKey(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TenantResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(tenantService.getById(id));
    }
}
