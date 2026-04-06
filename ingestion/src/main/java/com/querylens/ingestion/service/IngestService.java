package com.querylens.ingestion.service;

import com.querylens.ingestion.kafka.KafkaProducerService;
import com.querylens.infra.model.LogEnvelope;
import com.querylens.ingestion.parser.LogTimestampExtractor;
import com.querylens.tenantapiclient.cache.ApiKeyCacheService;
import com.querylens.tenantapiclient.cache.CachedTenant;
import com.querylens.tenantapiclient.model.TenantStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class IngestService {

    private final ApiKeyCacheService apiKeyCacheService;
    private final LogTimestampExtractor logTimestampExtractor;
    private final KafkaProducerService kafkaProducerService;

    @Autowired
    public IngestService(ApiKeyCacheService apiKeyCacheService,
                         LogTimestampExtractor logTimestampExtractor,
                         KafkaProducerService kafkaProducerService) {
        this.apiKeyCacheService = apiKeyCacheService;
        this.logTimestampExtractor = logTimestampExtractor;
        this.kafkaProducerService = kafkaProducerService;
    }

    public void ingest(String apiKey, String source, List<String> logs) {
        CachedTenant tenant = apiKeyCacheService.get(apiKey)
                .orElseThrow(() -> new IllegalArgumentException("Invalid API key"));
        if (tenant.status() != TenantStatus.ACTIVE) {
            throw new IllegalStateException("Tenant is suspended: " + tenant.tenantId());
        }

        Instant receivedAt = Instant.now();
        for (String logLine : logs) {
            Instant eventTime = logTimestampExtractor.extract(logLine);
            LogEnvelope envelope = new LogEnvelope(
                    tenant.tenantId(),
                    tenant.databaseType(),
                    eventTime,
                    receivedAt,
                    source,
                    logLine
            );
            kafkaProducerService.send(envelope);
        }
    }
}
