package com.querylens.infra.model;

import java.time.Instant;
import java.util.UUID;

public record LogEnvelope(
        UUID tenantId,
        DatabaseType dbType,
        Instant eventTime,
        Instant receivedAt,
        String source,
        String rawPayload
) {}
