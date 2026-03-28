# Query Lens

A multi-tenant SQL observability platform. Query Lens collects, routes, and analyses SQL query traffic across tenants — giving platform and infrastructure teams visibility into query patterns, latency, and anomalies without touching application code.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                        Clients                          │
└────────────────────┬────────────────────────────────────┘
                     │ HTTP
          ┌──────────▼──────────┐
          │     tenant-api      │  Tenant registration & API key management
          │     :8081           │
          └──────────┬──────────┘
                     │ (future: events)
          ┌──────────▼──────────┐
          │      ingestion      │  Query event intake & Kafka publishing
          └──────────┬──────────┘
                     │ Kafka (KRaft)
          ┌──────────▼──────────┐
          │   analysis-engine   │  Stream processing & anomaly detection
          └──────────┬──────────┘
                     │
          ┌──────────▼──────────┐
          │      PostgreSQL     │  Persistent store (per-module schema)
          └─────────────────────┘
```

### Modules

| Module            | Port  | Responsibility                                | Status   |
|-------------------|-------|-----------------------------------------------|----------|
| `tenant-api`      | 8081  | Register tenants, issue & validate API keys   | Active   |
| `ingestion`       | —     | Receive query events, publish to Kafka        | Planned  |
| `analysis-engine` | —     | Consume Kafka, detect patterns & anomalies    | Planned  |

### tenant-api endpoints

| Method | Path                           | Description           |
|--------|--------------------------------|-----------------------|
| POST   | `/api/v1/tenants/register`     | Register a new tenant |
| POST   | `/api/v1/tenants/validate-key` | Validate an API key   |
| GET    | `/api/v1/tenants/{id}`         | Fetch a tenant by ID  |

## Tech Stack

- **Java 25**, **Spring Boot 4.x**, Gradle multi-module
- **PostgreSQL 16** — primary datastore, schema managed by **Flyway**
- **Kafka 3.9 (KRaft)** — event streaming, no Zookeeper
- **Testcontainers** — integration tests spin up real Postgres containers
- **Helm** — Kubernetes packaging, supports local (OrbStack/minikube) and production (GKE) targets

---

## Quick Start — Local (Docker only)

**Prerequisites:** [OrbStack](https://orbstack.dev) (or Docker Desktop), Java 25

### 1. Start Postgres

```bash
./scripts/postgres-local.sh
# or with a custom DB name:
PG_DB=querylens ./scripts/postgres-local.sh
```

### 2. Run tenant-api

```bash
./gradlew :tenant-api:bootRun
```

The app starts on `http://localhost:8081`. Flyway runs migrations automatically on startup.

### 3. Smoke test

```bash
# Register a tenant
curl -s -X POST http://localhost:8081/api/v1/tenants/register \
  -H 'Content-Type: application/json' \
  -d '{"name":"acme","description":"test tenant"}' | jq .

# Validate the returned API key
curl -s -X POST http://localhost:8081/api/v1/tenants/validate-key \
  -H 'Content-Type: application/json' \
  -d '{"apiKey":"<apiKey from above>"}' | jq .
```

---

## Quick Start — Kubernetes (OrbStack / minikube)

**Prerequisites:** OrbStack with Kubernetes enabled, `helm`, `kubectl`

### 1. Build the image

```bash
./gradlew :tenant-api:bootBuildImage
```

### 2. Load image into local cluster

```bash
# OrbStack — image is already accessible from the local registry
# minikube:
minikube image load query-lens/tenant-api:0.1.0
```

### 3. Install the Helm chart

```bash
kubectl create namespace query-lens

helm install query-lens ./helm/query-lens \
  -f helm/query-lens/values.yaml \
  -f helm/query-lens/values-local.yaml \
  -n query-lens
```

### 4. Verify

```bash
kubectl get pods -n query-lens
kubectl logs -n query-lens deployment/tenant-api
```

### 5. Upgrade after code changes

```bash
./gradlew :tenant-api:bootBuildImage
helm upgrade query-lens ./helm/query-lens \
  -f helm/query-lens/values.yaml \
  -f helm/query-lens/values-local.yaml \
  -n query-lens
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
├── tenant-api/                  # Tenant management service
│   ├── src/main/
│   │   ├── java/                # Spring Boot application
│   │   └── resources/
│   │       ├── application.properties
│   │       └── db/migration/    # Flyway migrations
│   └── src/test/                # Integration tests (Testcontainers)
├── ingestion/                   # (planned)
├── analysis-engine/             # (planned)
├── helm/query-lens/             # Helm chart (Postgres, Kafka, tenant-api)
│   ├── values.yaml              # Default values
│   ├── values-local.yaml        # Local overrides (OrbStack/minikube)
│   └── values-prod.yaml         # Production overrides (GKE)
├── scripts/
│   └── postgres-local.sh        # Spin up local Postgres via Docker
└── build.gradle                 # Root Gradle build (multi-module)
```
