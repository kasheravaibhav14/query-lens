# Test Instructions

## tenant-api — Integration Tests

### Setup

Requires Docker (Testcontainers starts PostgreSQL automatically).
Kafka is NOT required for integration tests — `KafkaTopicProvisioningService` is not exercised in the current test suite (Kafka provisioning is async fire-and-forget).

### Run

```bash
./gradlew :tenant-api:test
```

### Expected outcome

All tests in `TenantControllerIntegrationTest` and `TenantServiceIntegrationTest` pass.

---

## infra module — Cache refresh endpoint

### Setup

Start `tenant-api` locally with PostgreSQL running:

```bash
./scripts/postgres-local.sh start
./gradlew :tenant-api:bootRun
```

### Test cache refresh via HTTP

```bash
# Refresh all TENANT_API_KEYS cache entries
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8081/internal/cache/refresh/TENANT_API_KEYS
# Expected: 204
```

---

## tenant-api — Async Kafka topic provisioning

### Setup

Requires Kafka running locally. Using the Helm chart (minikube):

```bash
kubectl port-forward svc/query-lens-kafka 9092:9092
```

Or start a local Kafka container:

```bash
docker run -d --name kafka -p 9092:9092 \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  apache/kafka:3.9.0
```

### Test topic creation

```bash
# Register a tenant
curl -s -X POST http://localhost:8081/tenants/register \
  -H "Content-Type: application/json" \
  -d '{"name":"test-tenant","databaseType":"MONGODB"}'

# Verify topic was created (replace <tenantId> with the UUID from the response)
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe --topic logs.<tenantId>
```

### Expected outcome

Topic `logs.<tenantId>` exists with 3 partitions and replication factor 1.
If Kafka is unavailable, `tenant-api` still responds with 201 — provisioning fails silently
after 3 retries and logs an error (check application logs).

---

## ingestion — HTTP ingest endpoint + Kafka produce

### Setup

Start all local services:

```bash
./scripts/postgres-local.sh
./scripts/clickhouse-local.sh
docker run -d --name kafka-local -p 9092:9092 \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e CLUSTER_ID=MkU3OEVoblNTdGUwalVoaQ \
  apache/kafka:3.9.0
./gradlew :tenant-api:bootRun
./gradlew :ingestion:bootRun
```

### Test ingestion

```bash
# 1. Register a tenant, capture the API key
RESPONSE=$(curl -s -X POST http://localhost:8081/api/v1/tenants/register \
  -H 'Content-Type: application/json' \
  -d '{"name":"acme","databaseType":"MONGODB"}')
API_KEY=$(echo $RESPONSE | jq -r '.apiKey')

# 2. Ingest a log line — expects 204 No Content
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8082/ingest \
  -H "X-Api-Key: $API_KEY" \
  -H "X-Source: test" \
  -H "Content-Type: application/json" \
  -d '{"logs":["{ \"t\": { \"$date\": \"2024-01-15T10:00:00.000Z\" }, \"msg\": \"command test\" }"]}'
# Expected: 204

# 3. Invalid API key returns 401
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8082/ingest \
  -H "X-Api-Key: ql_invalid" \
  -H "Content-Type: application/json" \
  -d '{"logs":["anything"]}'
# Expected: 401

# 4. Missing API key header returns 401
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8082/ingest \
  -H "Content-Type: application/json" \
  -d '{"logs":["anything"]}'
# Expected: 401
```

### Verify Kafka message

```bash
# Consume one message from the tenant's topic (replace <tenantId> with UUID from registration)
docker exec kafka-local /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic logs.<tenantId> \
  --from-beginning \
  --max-messages 1
# Expected: JSON bytes of a LogEnvelope
```

---

## End-to-end smoke test (all services)

Runs the full pipeline: register tenant → ingest MongoDB COLLSCAN logs → verify `collscan_missing_index` finding.

### Setup

All three services must be running (tenant-api :8081, ingestion :8082, analysis-engine :8083) plus Kafka + Postgres. Requires `jq`.

### Run

```bash
# Local
./scripts/smoke-test.sh

# Against Raspberry Pi (or any remote host)
./scripts/smoke-test.sh --host 192.168.1.100
```

### Expected outcome

```
[PASS] Registered tenant: tenantId=<uuid>
[PASS] Ingest accepted (204)
[PASS] Invalid API key correctly rejected (401)
[PASS] Analyze returned 200
[PASS] Rule fired: collscan_missing_index
```

If findings come back empty, the pipeline processed before the window filled — wait a few seconds and re-run the ingest + analyze steps manually, or just re-run the script (it registers a new tenant each time).

---

## ClickHouse — local setup

### Setup

```bash
./scripts/clickhouse-local.sh
```

### Run DDL migration manually

```bash
docker exec clickhouse-local clickhouse-client \
  --query "$(cat clickhouse/migrations/V1__create_log_events.sql)"
```

### Verify table was created

```bash
docker exec clickhouse-local clickhouse-client \
  --query "DESCRIBE TABLE log_events"
# Expected: columns tenant_id, db_type, event_time, received_at, source, raw_payload
```
