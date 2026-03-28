package com.querylens.tenantapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.querylens.tenantapi.AbstractIntegrationTest;
import com.querylens.tenantapi.dto.RegisterTenantRequest;
import com.querylens.tenantapi.dto.RegisterTenantResponse;
import com.querylens.tenantapi.model.DatabaseType;
import com.querylens.tenantapi.repository.TenantRepository;
import com.querylens.tenantapi.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TenantControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantService tenantService;
    @Autowired TenantRepository tenantRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        tenantRepository.deleteAll();
    }

    @Test
    void register_returns201WithApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterTenantRequest("my-tenant", "a description", DatabaseType.POSTGRES))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").value(startsWith("ql_")))
                .andExpect(jsonPath("$.name").value("my-tenant"))
                .andExpect(jsonPath("$.databaseType").value("POSTGRES"));
    }

    @Test
    void register_returns409OnDuplicateName() throws Exception {
        tenantService.register(new RegisterTenantRequest("taken", null, DatabaseType.POSTGRES));

        mockMvc.perform(post("/api/v1/tenants/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterTenantRequest("taken", null, DatabaseType.POSTGRES))))
                .andExpect(status().isConflict());
    }

    @Test
    void register_returns400OnInvalidName() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterTenantRequest("-bad-name", null, DatabaseType.POSTGRES))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").exists());
    }

    @Test
    void validateKey_returns200ForValidKey() throws Exception {
        RegisterTenantResponse registered = tenantService.register(new RegisterTenantRequest("valid-svc", null, DatabaseType.POSTGRES));

        mockMvc.perform(post("/api/v1/tenants/validate-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("apiKey", registered.apiKey()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    void validateKey_returns401ForUnknownKey() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/validate-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("apiKey", "ql_" + "b".repeat(64)))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validateKey_returns400ForMalformedKey() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/validate-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("apiKey", "not-a-valid-key"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getById_returns200() throws Exception {
        RegisterTenantResponse registered = tenantService.register(new RegisterTenantRequest("fetch-me", null, DatabaseType.POSTGRES));

        mockMvc.perform(get("/api/v1/tenants/" + registered.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("fetch-me"));
    }

    @Test
    void getById_returns404ForUnknownId() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
