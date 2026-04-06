# Query Lens

A multi-tenant real-time MongoDB slow query analyzer. Query Lens streams MongoDB Logv2 logs, normalizes them into structured events, detects query anti-patterns (missing indexes, retry storms, resource contention), and surfaces findings via a REST API — before they reach production.

## Architecture

```
MongoDB Logv2 logs (streamed via HTTP / future: gRPC)
        │
        ▼
┌──────────────────┐
│    ingestion     │  API key validation → LogEnvelope → Kafka
│    :8082         │
└────────┬─────────┘
         │ Kafka topic: logs.{tenantId}
         ▼
┌──────────────────┐
│ analysis-engine  │  MongoQueryLogExtractor → InMemoryWindowStore
│    :8083         │  → ClickHouseWriteBuffer → rule engine → /analyze
└────────┬─────────┘
         │
         ├──► ClickHouse  (mongo_log_events, 7-day TTL)
         └──► /analyze API
```

### Modules

| Module               | Port  | Responsibility                                       | Status   |
|----------------------|-------|------------------------------------------------------|----------|
| `infra`              | —     | Shared auto-config (codecs, cache, virtual-thread executor) | Active |
| `tenant-api-client`  | —     | Shared JPA model, repo, API key cache (1h TTL)       | Active   |
| `tenant-api`         | 8081  | Register tenants, issue & validate API keys          | Active   |
| `ingestion`          | 8082  | Receive log events, publish `LogEnvelope` to Kafka   | Active   |
| `analysis-engine`    | 8083  | Consume Kafka → parse → window store → ClickHouse → rule engine → `/analyze` | Active |

### tenant-api endpoints

| Method | Path                           | Description           |
|--------|--------------------------------|-----------------------|
| POST   | `/api/v1/tenants/register`     | Register a new tenant |
| POST   | `/api/v1/tenants/validate-key` | Validate an API key   |
| GET    | `/api/v1/tenants/{id}`         | Fetch a tenant by ID  |

### ingestion endpoints

| Method | Path      | Description                              |
|--------|-----------|------------------------------------------|
| POST   | `/ingest` | Ingest MongoDB Logv2 log lines (batch)   |

### analysis-engine endpoints

| Method | Path       | Description                                      |
|--------|------------|--------------------------------------------------|
| GET    | `/analyze` | Detect patterns for a tenant (rule engine output) |

## Tech Stack

- **Java 25**, **Spring Boot 4.x**, Gradle multi-module, **virtual threads** (Project Loom)
- **PostgreSQL 16** — tenant registry, schema managed by **Flyway**
- **Kafka 3.9 (KRaft)** — one topic per tenant (`logs.{tenantId}`), no Zookeeper
- **ClickHouse 24.3** — columnar time-series store (`mongo_log_events`, `ORDER BY (tenant_id, timestamp)`)
- **Testcontainers** — integration tests spin up real Postgres containers
- **Helm** — Kubernetes packaging for minikube / Linux (k3s) / GKE

---

## Local Development (Mac)

### Prerequisites

- Docker ([OrbStack](https://orbstack.dev) or Docker Desktop)
- Java 25
- Gradle (wrapper included — use `./gradlew`)

### Start local services

```bash
./scripts/postgres-local.sh       # PostgreSQL on :5432
./scripts/clickhouse-local.sh     # ClickHouse on :8123 / :9000
```

Kafka:

```bash
docker run -d --name kafka-local -p 9092:9092 \
  -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e CLUSTER_ID=MkU3OEVoblNTdGUwalVoaQ \
  apache/kafka:3.9.0
```

### Run services

```bash
./gradlew :tenant-api:bootRun       # http://localhost:8081
./gradlew :ingestion:bootRun        # http://localhost:8082
./gradlew :analysis-engine:bootRun  # http://localhost:8083
```

Flyway runs Postgres migrations automatically when `tenant-api` starts.
ClickHouse migrations must be applied once manually:

```bash
docker exec -i clickhouse-local clickhouse-client < clickhouse/migrations/V1__create_log_events.sql
docker exec -i clickhouse-local clickhouse-client < clickhouse/migrations/V2__create_mongo_log_events.sql
```

### Smoke test

```bash
# 1. Register a tenant
TENANT=$(curl -s -X POST http://localhost:8081/api/v1/tenants/register \
  -H 'Content-Type: application/json' \
  -d '{"name":"acme","description":"test","databaseType":"MONGODB"}')
echo $TENANT | jq .
API_KEY=$(echo $TENANT | jq -r '.apiKey')

# 2. Ingest a MongoDB slow query log line
curl -s -X POST http://localhost:8082/ingest \
  -H "X-Api-Key: $API_KEY" \
  -H 'X-Source: order-service' \
  -H 'Content-Type: application/json' \
  -d '{
    "logs": [
      "{\"t\":{\"$date\":\"2024-01-15T10:00:00.000Z\"},\"s\":\"I\",\"c\":\"COMMAND\",\"ctx\":\"conn1\",\"msg\":\"Slow query\",\"attr\":{\"ns\":\"mydb.orders\",\"command\":{\"find\":\"orders\"},\"planSummary\":\"COLLSCAN\",\"keysExamined\":0,\"docsExamined\":50000,\"nreturned\":5,\"durationMillis\":430}}"
    ]
  }'

# 3. Query analysis (once rule engine is wired)
TENANT_ID=$(echo $TENANT | jq -r '.tenantId')
curl -s "http://localhost:8083/analyze?tenantId=$TENANT_ID" | jq .
```

### Run tests

```bash
./gradlew :tenant-api:test       # requires Docker (Testcontainers)
```

---

## Deploy on Raspberry Pi / Linux Server (no Docker registry needed)

Two paths depending on whether you want Kubernetes or plain Docker Compose.

### Path A — Docker Compose (simplest, recommended for Pi)

No Helm, no registry. Infrastructure runs in containers; Spring Boot services run as JARs.

```bash
# 1. Bootstrap the machine (Docker + Java 25)
chmod +x scripts/setup-linux.sh
./scripts/setup-linux.sh

# Re-login or: newgrp docker

# 2. Start infrastructure (Postgres, Kafka, ClickHouse)
docker compose up -d

# 3. Build and run services (see "Deploy on a Linux Server" section below)
```

### Path B — k3s + Helm (Kubernetes, no external registry)

k3s is a lightweight single-binary Kubernetes that runs well on Pi 4/5.
Images are imported directly into k3s's containerd — **no registry, no `docker push`**.

```bash
# 1. Bootstrap the machine (Docker + Java 25 + k3s + Helm + local registry)
chmod +x scripts/setup-linux.sh
./scripts/setup-linux.sh --k3s

# Re-login or: newgrp docker

# 2. Build images + import into k3s + Helm deploy (one command)
chmod +x scripts/deploy-k3s.sh
./scripts/deploy-k3s.sh
```

**How it works without a registry:**
The `deploy-k3s.sh` script:
1. Builds OCI images via Spring Boot's Cloud Native Buildpacks (`bootBuildImage`) — no Dockerfile needed
2. Pipes each image directly into k3s containerd: `docker save <image> | sudo k3s ctr images import -`
3. Sets `imagePullPolicy: Never` at deploy time so k3s never tries to pull from a registry

Re-run `./scripts/deploy-k3s.sh` after every code change to rebuild + redeploy.

> **Hardware requirements:** Raspberry Pi 4/5 with **4 GB RAM minimum**, running **64-bit OS** (Raspberry Pi OS 64-bit or Ubuntu 22.04+). ClickHouse and Java 25 do not support 32-bit ARM.

---

## Deploy on a Linux Server

This setup uses `docker-compose.yml` for infrastructure (Postgres, Kafka, ClickHouse) and runs the Spring Boot services as JAR processes (or systemd units).

### Prerequisites

```bash
# Java 25
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25-tem

# Docker + Docker Compose
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER   # re-login after this

# Verify
java -version
docker compose version
```

### 1. Clone and build

```bash
git clone <repo-url> query-lens
cd query-lens
./gradlew build -x test
```

### 2. Start infrastructure

```bash
# If Kafka must be reachable from external clients, set KAFKA_HOST to the server's IP.
# For services running on the same machine, the default (localhost) works.
export KAFKA_HOST=localhost   # or: export KAFKA_HOST=$(hostname -I | awk '{print $1}')

docker compose up -d
docker compose ps   # all services should be healthy within ~30s
```

ClickHouse migrations run automatically on first start via `docker-entrypoint-initdb.d`.
Postgres migrations run via Flyway when `tenant-api` starts.

### 3. Set environment and run services

Each service reads config from `application.properties` but any property can be overridden via environment variable (Spring Boot convention: dots → underscores, uppercased).

```bash
# tenant-api
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/querylens
export SPRING_DATASOURCE_USERNAME=querylens
export SPRING_DATASOURCE_PASSWORD=querylens
java -jar tenant-api/build/libs/tenant-api-0.1.0.jar &

# ingestion
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/querylens
export SPRING_DATASOURCE_USERNAME=querylens
export SPRING_DATASOURCE_PASSWORD=querylens
export SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
java -jar ingestion/build/libs/ingestion-0.1.0.jar &

# analysis-engine
export SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export QUERY_LENS_CLICKHOUSE_ENABLED=true
export QUERY_LENS_CLICKHOUSE_URL=jdbc:clickhouse://localhost:8123/default
java -jar analysis-engine/build/libs/analysis-engine-0.1.0.jar &
```

### 4. Verify

```bash
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
```

### 5. Run as systemd services (optional, for persistent deployment)

Create `/etc/systemd/system/query-lens-tenant-api.service`:

```ini
[Unit]
Description=Query Lens — tenant-api
After=network.target docker.service

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/query-lens
Environment="SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/querylens"
Environment="SPRING_DATASOURCE_USERNAME=querylens"
Environment="SPRING_DATASOURCE_PASSWORD=querylens"
ExecStart=/home/ubuntu/.sdkman/candidates/java/current/bin/java -jar tenant-api/build/libs/tenant-api-0.1.0.jar
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Repeat for `ingestion` and `analysis-engine` with their respective env vars. Then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now query-lens-tenant-api
sudo systemctl enable --now query-lens-ingestion
sudo systemctl enable --now query-lens-analysis-engine
```

### 6. Upgrade

```bash
cd query-lens
git pull
./gradlew build -x test
sudo systemctl restart query-lens-tenant-api query-lens-ingestion query-lens-analysis-engine
```

---

## Quick Start — Kubernetes (minikube / OrbStack)

**Prerequisites:** minikube or OrbStack with Kubernetes enabled, `helm`, `kubectl`

### 1. Build images

```bash
./gradlew :tenant-api:bootBuildImage
./gradlew :ingestion:bootBuildImage
./gradlew :analysis-engine:bootBuildImage
```

### 2. Load images into local cluster

```bash
# minikube:
minikube image load query-lens/tenant-api:0.1.0
minikube image load query-lens/ingestion:0.1.0
minikube image load query-lens/analysis-engine:0.1.0
# OrbStack: images from local Docker daemon are already accessible
```

### 3. Install the Helm chart

```bash
kubectl create namespace query-lens

helm install query-lens ./helm/query-lens \
  -f helm/query-lens/values.yaml \
  -n query-lens
```

### 4. Apply ClickHouse migrations

```bash
kubectl exec -n query-lens statefulset/clickhouse -- \
  clickhouse-client < clickhouse/migrations/V1__create_log_events.sql
kubectl exec -n query-lens statefulset/clickhouse -- \
  clickhouse-client < clickhouse/migrations/V2__create_mongo_log_events.sql
```

### 5. Verify

```bash
kubectl get pods -n query-lens
kubectl logs -n query-lens deployment/tenant-api
kubectl logs -n query-lens deployment/analysis-engine
```

### 6. Upgrade after code changes

```bash
./gradlew :tenant-api:bootBuildImage :ingestion:bootBuildImage :analysis-engine:bootBuildImage
helm upgrade query-lens ./helm/query-lens -f helm/query-lens/values.yaml -n query-lens
```

---

## Running Tests

Integration tests use Testcontainers — Docker must be running. No manual setup needed.

```bash
./gradlew :tenant-api:test
```

---

## Project Structure

```
query-lens/
├── infra/                       # Shared auto-config (codecs, cache, virtual-thread executor)
├── tenant-api-client/           # Shared JPA entity, repo, API key cache
├── tenant-api/                  # Tenant management service (:8081)
│   ├── src/main/java/
│   └── src/main/resources/
│       ├── application.properties
│       └── db/migration/        # Flyway migrations (Postgres)
├── ingestion/                   # Log ingestion service (:8082)
├── analysis-engine/             # Stream processing + rule engine (:8083)
│   └── src/main/java/
│       ├── extractor/           # MongoQueryLogExtractor (Logv2 parsing)
│       ├── store/               # InMemoryWindowStore
│       ├── clickhouse/          # ClickHouseWriteBuffer, MongoLogEventRepository
│       └── kafka/               # LogEventConsumer
├── clickhouse/migrations/       # ClickHouse DDL (V1, V2)
├── helm/query-lens/             # Helm chart (Postgres, Kafka, ClickHouse, all services)
│   └── values.yaml
├── docker-compose.yml           # Infrastructure for Linux / local dev
├── gradle/
│   └── libs.versions.toml       # Version catalog — all dependency versions in one place
├── scripts/
│   ├── postgres-local.sh
│   └── clickhouse-local.sh
└── build.gradle
```
