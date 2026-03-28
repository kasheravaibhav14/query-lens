# Helm Chart — Test Instructions

## Prerequisites

- [minikube](https://minikube.sigs.k8s.io/docs/start/) installed and running
- [Helm 3](https://helm.sh/docs/intro/install/) installed
- [kubectl](https://kubernetes.io/docs/tasks/tools/) installed

```bash
minikube start
minikube addons enable ingress    # enables NGINX Ingress Controller
```

---

## Step 1 — Validate chart templates (no cluster needed)

```bash
helm template ql ./helm/query-lens -f helm/query-lens/values.yaml -f helm/query-lens/values-local.yaml
```

**Expected:** All templates render without errors. You'll see the full YAML for every resource printed to stdout.

---

## Step 2 — Lint the chart

```bash
helm lint ./helm/query-lens -f helm/query-lens/values.yaml -f helm/query-lens/values-local.yaml
```

**Expected:** `1 chart(s) linted, 0 chart(s) failed`

---

## Step 3 — Build and load the tenant-api image

```bash
./gradlew :tenant-api:bootBuildImage
minikube image load query-lens/tenant-api:0.1.0
```

**Expected:** Image appears in minikube's Docker: `minikube image ls | grep tenant-api`

---

## Step 4 — Install the chart

```bash
kubectl create namespace query-lens

helm install ql ./helm/query-lens \
  -f helm/query-lens/values.yaml \
  -f helm/query-lens/values-local.yaml \
  -n query-lens
```

**Expected output includes:**
```
Release: ql
Namespace: query-lens
...
```

---

## Step 5 — Verify all pods come up

```bash
kubectl get pods -n query-lens -w
```

**Expected:** All pods reach `Running` status (may take 60–90s on first pull).

| Pod | Expected Status |
|---|---|
| `postgres-0` | Running |
| `kafka-0` | Running |
| `tenant-api-*` | Running |

---

## Step 6 — Test tenant-api via Ingress

```bash
# Get minikube IP and add to /etc/hosts
echo "$(minikube ip)  query-lens.local" | sudo tee -a /etc/hosts

# Hit the tenant endpoint
curl -s http://query-lens.local/api/tenants
```

**Expected:** HTTP 200 with empty JSON array `[]` or tenant list (Flyway migrations run automatically on startup).

---

## Step 7 — Test Kafka connectivity

```bash
# Install kcat if needed: brew install kcat
kcat -b $(minikube ip):30092 -L
```

**Expected:** Broker metadata listing showing `kafka-0` as broker.

---

## Step 8 — Test Postgres via port-forward

```bash
kubectl port-forward -n query-lens svc/postgres 5432:5432 &
psql -h localhost -U querylens -d querylens
# password: changeme
```

**Expected:** psql connects and shows `querylens=>` prompt. `\dt` should show the `tenants` table created by Flyway.

---

## Teardown

```bash
helm uninstall ql -n query-lens
kubectl delete namespace query-lens
```

**Expected:** All resources removed. PVCs are deleted with the namespace.
