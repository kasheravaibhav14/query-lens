package com.querylens.analysisengine.model;

import com.querylens.infra.model.DatabaseType;
import com.querylens.infra.model.QueryLog;

import java.time.Instant;
import java.util.UUID;

public record MongoQueryLog(
        UUID tenantId,
        DatabaseType dbType,
        Instant timestamp,
        Instant receivedAt,
        long durationMillis,
        String namespace,
        String service,
        // MongoDB-specific
        String planSummary,
        int keysExamined,
        int docsExamined,
        int nreturned,
        String command,   // attr.command serialized as JSON string
        String dbNode     // from log ctx field (e.g. "conn12345")
) implements QueryLog {}
