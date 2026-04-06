-- Per-db-type schema (Option B). Supersedes the generic log_events table from V1.
-- Partition by date only — ClickHouse recommends ≤1000 partitions per table.
-- tenant_id first in ORDER BY: per-tenant data is physically co-located,
-- so WHERE tenant_id = ? AND timestamp BETWEEN ... hits minimal granules.
CREATE TABLE IF NOT EXISTS mongo_log_events
(
    tenant_id       UUID,
    timestamp       DateTime64(3, 'UTC'),
    received_at     DateTime64(3, 'UTC'),
    service         LowCardinality(String),
    namespace       String,
    duration_millis Int64,
    plan_summary    LowCardinality(Nullable(String)),
    keys_examined   Int32,
    docs_examined   Int32,
    nreturned       Int32,
    command         String,
    db_node         LowCardinality(Nullable(String))
) ENGINE = MergeTree()
PARTITION BY toYYYYMMDD(timestamp)
ORDER BY (tenant_id, timestamp)
TTL timestamp + INTERVAL 7 DAY;
