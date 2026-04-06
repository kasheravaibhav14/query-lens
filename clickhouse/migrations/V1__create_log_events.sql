CREATE TABLE IF NOT EXISTS log_events
(
    tenant_id   UUID,
    db_type     LowCardinality(String),
    event_time  DateTime64(3, 'UTC'),
    received_at DateTime64(3, 'UTC'),
    source      String,
    raw_payload String
) ENGINE = MergeTree()
PARTITION BY (tenant_id, toYYYYMMDD(event_time))
ORDER BY (tenant_id, event_time)
TTL event_time + INTERVAL 7 DAY;
