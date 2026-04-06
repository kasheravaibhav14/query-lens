package com.querylens.infra.model;

import java.time.Instant;
import java.util.UUID;

public interface QueryLog {
    UUID tenantId();
    DatabaseType dbType();
    Instant timestamp();
    long durationMillis();
    String namespace();
    String service();
}
