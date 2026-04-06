# Ingestion Layer + ClickHouse Setup — Design Spec
_Date: 2026-03-29_

## Scope

This spec covers:
1. New `tenant-api-client` module — Spring auto-config library for external API key validation
2. New `ingestion` module — Spring Boot HTTP ingest endpoint → Kafka producer
3. `infra` additions — `EventCodec` interface + `JsonEventCodec`
4. ClickHouse local setup — Docker script + DDL + Helm chart
5. `tenant-api` simplification — remove Guava cache, restore direct DB validation
6. README.md beginner setup section

Out of scope: analysis-engine Kafka consumer, ClickHouse write path, rule engine, gRPC endpoint.

---

## Module Dependency Graph

```
infra
  ↑
tenant-api          (no tenant-api-client — direct DB validation)

infra
  ↑
tenant-api-client   (library: enums, CachedTenant, TenantApiClient, ApiKeyCacheService)
  ↑
ingestion           (Spring Boot app: HTTP ingest → Kafka)
```

---

## 1. `infra` additions

### New: `EventCodec` interface + `JsonEventCodec`

Package: `com.querylens.infra.codec`

```java
public interface EventCodec {
    byte[] serialize(Object event);
    <T> T deserialize(byte[] bytes, Class<T> type);
}

@Primary
public class JsonEventCodec implements EventCodec {
    // Jackson ObjectMapper
}
```

Both declared as `@Bean` in `InfraAutoConfiguration`. `@Primary` ensures JSON wins unless a `@Qualifier` selects another.

To swap in Avro/Protobuf later: add `@Service @Qualifier("avro") class AvroEventCodec implements EventCodec`, update injection sites.

`infra/build.gradle` gains: `implementation 'com.fasterxml.jackson.core:jackson-databind'`

---

## 2. `tenant-api-client` module

Spring Boot auto-config library. Never deployed standalone. Provides working beans to any importer.

### build.gradle
- `java-library` + `org.springframework.boot` (bootJar disabled, jar enabled)
- Dependencies: `project(':infra')`, `spring-context`, `spring-web`, `guava`

### Package: `com.querylens.tenantapiclient`

```
model/
  TenantStatus.java        enum: ACTIVE, SUSPENDED
  DatabaseType.java        enum: MONGODB (others deferred)
  CachedTenant.java        record: tenantId, name, status, databaseType

client/
  TenantApiClient.java     @Service, Spring RestClient
                           URL from: query-lens.tenant-api.url
                           POST /api/v1/tenants/validate-key → ValidateKeyResponse
                           maps response: id→tenantId, name, status, databaseType → CachedTenant
  ValidateKeyResponse.java internal record matching tenant-api's TenantResponse shape:
                           UUID id, String name, TenantStatus status, DatabaseType databaseType

cache/
  ApiKeyCacheService.java  @Service, Guava LoadingCache 1h TTL
                           implements CacheRefreshable (LocalCacheType.TENANT_API_KEYS)
                           cache miss → TenantApiClient.validate(rawApiKey)

TenantApiClientAutoConfiguration.java   @AutoConfiguration
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

### How importers configure it

Each importer sets in `application.properties`:
```properties
query-lens.tenant-api.url=http://localhost:8081   # tenant-api local
# or
query-lens.tenant-api.url=http://tenant-api:8081  # ingestion → tenant-api in-cluster
```

`ApiKeyCacheService` is injected as-is — no subclassing required.

---

## 3. `tenant-api` simplification

- Remove `cache/ApiKeyCacheService.java` (Guava cache)
- Remove `cache/` package entirely
- Remove `util/HashUtils.java` reference in cache service (keep in `TenantService` for registration)
- `TenantService.validateApiKey()` reverts to direct `TenantRepository.findByApiKeyHash()` + status check
- Remove `infra` project dependency (no longer needs `CacheRefreshable`)
- Remove `guava` dependency

`TenantStatus` and `DatabaseType` enums move to `tenant-api-client`. `tenant-api` gains `implementation project(':tenant-api-client')` to import them back.

---

## 4. `ingestion` module

Spring Boot app. Port 8082. Depends on `infra` + `tenant-api-client` + Kafka.

### build.gradle
```
project(':infra')
project(':tenant-api-client')
spring-boot-starter-web
spring-boot-starter-actuator
spring-kafka
```

### Package: `com.querylens.ingestion`

```
IngestionApplication.java

controller/
  IngestController.java     @RestController, POST /ingest
  IngestRequest.java        record: List<String> logs

service/
  IngestService.java        @Service: auth → parse → envelope → produce

model/
  LogEnvelope.java          record:
                              UUID tenantId
                              String dbType
                              Instant eventTime   ← from t.$date
                              Instant receivedAt  ← Instant.now() at receipt
                              String source       ← X-Source header
                              String rawPayload   ← verbatim log line

kafka/
  KafkaProducerService.java  @Service, KafkaTemplate<String, byte[]>
                             topic: "logs.{tenantId}"
                             value: EventCodec.serialize(envelope)

parser/
  LogTimestampExtractor.java  parses t.$date from Logv2 JSON
                              fallback: Instant.now()

config/
  KafkaProducerConfig.java    @Configuration, KafkaTemplate bean
```

### Request flow

```
POST /ingest
  Header: X-Api-Key: ql_<hex>
  Header: X-Source: my-service
  Body:   { "logs": ["<logv2 json line>", ...] }

IngestController
  → IngestService.ingest(apiKey, source, logs)
      → ApiKeyCacheService.get(apiKey) → CachedTenant  [or throw 401]
      → for each log line:
          eventTime = LogTimestampExtractor.extract(line)  [fallback: now()]
          envelope  = new LogEnvelope(tenantId, dbType, eventTime, now(), source, line)
          KafkaProducerService.send(envelope)
  → 204 No Content
```

### Error responses

| Condition | Status |
|---|---|
| Missing / invalid API key | 401 Unauthorized |
| Tenant suspended | 403 Forbidden |
| Malformed request body | 400 Bad Request |

### gRPC

TODO — not implemented. Will be a second ingest transport using the same `IngestService`.

---

## 5. ClickHouse setup

### `scripts/clickhouse-local.sh`

Same pattern as `postgres-local.sh`:
- Idempotent: running → no-op, exited → restart, missing → docker run
- Image: `clickhouse/clickhouse-server:24.3`
- Port: 8123 (HTTP interface), 9000 (native TCP)
- Waits for ready via `clickhouse-client --query "SELECT 1"`
- Prints connection string on success

Env vars (all optional):
```
CH_HOST      default: localhost
CH_HTTP_PORT default: 8123
CH_TCP_PORT  default: 9000
CH_PASSWORD  default: (empty — ClickHouse default)
CH_VERSION   default: 24.3
CONTAINER    default: clickhouse-local
```

### DDL — `clickhouse/migrations/V1__create_log_events.sql`

```sql
CREATE TABLE IF NOT EXISTS log_events (
    tenant_id    UUID,
    db_type      LowCardinality(String),
    event_time   DateTime64(3, 'UTC'),
    received_at  DateTime64(3, 'UTC'),
    source       String,
    raw_payload  String
) ENGINE = MergeTree()
PARTITION BY (tenant_id, toYYYYMM(event_time))
ORDER BY (tenant_id, event_time)
TTL event_time + INTERVAL 7 DAY;
```

**Why MergeTree (not ReplacingMergeTree):** events are append-only. No re-ingestion scenario.
**Why `LowCardinality(String)` for `db_type`:** few distinct values → better compression.
**Why 7-day TTL:** QA data stales fast. Configurable later.
**No extracted fields yet:** `duration_ms`, `plan_summary`, etc. are parsed by `analysis-engine` from `raw_payload`. Schema kept minimal until consumer is built.

### Helm — `helm/query-lens/templates/clickhouse/`

```
statefulset.yaml   image: clickhouse/clickhouse-server:24.3
                   ports: 8123 (http), 9000 (tcp)
                   volumeClaimTemplate for data
service.yaml       ClusterIP, ports 8123 + 9000
```

`values.yaml` additions:
```yaml
clickhouse:
  image:
    repository: clickhouse/clickhouse-server
    tag: "24.3"
  storage:
    size: 5Gi
    storageClass: standard
  resources:
    requests: { cpu: 200m, memory: 512Mi }
    limits:   { cpu: 1000m, memory: 2Gi }
```

---

## 6. README.md

Single "Local Development" section added:

```markdown
## Local Development

### Prerequisites
- Docker (OrbStack or Docker Desktop)
- Java 25
- Gradle (wrapper included — use `./gradlew`)

### Start local services

\`\`\`bash
./scripts/postgres-local.sh       # PostgreSQL on :5432
./scripts/clickhouse-local.sh     # ClickHouse on :8123 / :9000
\`\`\`

Kafka (one-liner):
\`\`\`bash
docker run -d --name kafka-local -p 9092:9092 \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e CLUSTER_ID=MkU3OEVoblNTdGUwalVoaQ \
  apache/kafka:3.9.0
\`\`\`

### Run services

\`\`\`bash
./gradlew :tenant-api:bootRun    # http://localhost:8081
./gradlew :ingestion:bootRun     # http://localhost:8082
\`\`\`

### Run tests

\`\`\`bash
./gradlew :tenant-api:test       # requires Docker (Testcontainers)
\`\`\`
```

---

## Implementation order

1. `infra` — add `EventCodec` + `JsonEventCodec`
2. `tenant-api-client` — new module (enums, CachedTenant, TenantApiClient, ApiKeyCacheService)
3. `tenant-api` — remove cache layer, import enums from `tenant-api-client`
4. `ingestion` — full implementation
5. ClickHouse — script + DDL + Helm
6. README.md
