#!/bin/bash
# deploy-k3s.sh — Build images and deploy query-lens to k3s via Helm.
# Run this from the repo root after setup-linux.sh --k3s has been executed.
#
# How images work without an external registry:
#   k3s uses containerd, not the Docker daemon. Images built with Buildpacks
#   live in Docker's image store. This script:
#     1. Builds each Spring Boot image via Cloud Native Buildpacks (bootBuildImage)
#     2. Exports from Docker → pipes into k3s containerd (no registry needed)
#     3. Helm deploys with imagePullPolicy=Never so k3s never tries to pull
#
# Re-run this script after every code change to rebuild + redeploy.

set -euo pipefail

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}[deploy]${NC} $1"; }
warn() { echo -e "${YELLOW}[warn]  ${NC} $1"; }

NAMESPACE=query-lens
CHART=./helm/query-lens
VERSION=0.0.1-SNAPSHOT   # matches version in build.gradle

# ── Prereq check ──────────────────────────────────────────────────────────────
command -v k3s  &>/dev/null || { echo "k3s not found. Run: ./scripts/setup-linux.sh --k3s"; exit 1; }
command -v helm &>/dev/null || { echo "helm not found. Run: ./scripts/setup-linux.sh --k3s"; exit 1; }

# ── Build JARs ────────────────────────────────────────────────────────────────
log "Building all modules..."
# shellcheck source=/dev/null
source "$HOME/.sdkman/bin/sdkman-init.sh"
./gradlew build -x test

# ── Build OCI images (Cloud Native Buildpacks — no Dockerfile needed) ─────────
MODULES=(tenant-api ingestion analysis-engine)
for module in "${MODULES[@]}"; do
  log "Building OCI image for $module..."
  ./gradlew ":$module:bootBuildImage"
done

# ── Import images into k3s containerd ─────────────────────────────────────────
# k3s containerd is separate from Docker. We pipe the image tarball directly.
# imagePullPolicy=Never in values.yaml tells k3s to use this local image.
for module in "${MODULES[@]}"; do
  IMAGE="query-lens/${module}:${VERSION}"
  log "Importing $IMAGE into k3s..."
  docker save "$IMAGE" | sudo k3s ctr images import -
done
log "All images imported into k3s containerd"

# ── Apply ClickHouse migrations (first deploy only) ───────────────────────────
if ! kubectl get ns "$NAMESPACE" &>/dev/null; then
  log "Namespace $NAMESPACE not found yet — will be created by Helm"
fi

# ── Helm install / upgrade ────────────────────────────────────────────────────
kubectl create namespace "$NAMESPACE" 2>/dev/null || true

log "Running helm upgrade --install..."
helm upgrade --install query-lens "$CHART" \
  --namespace "$NAMESPACE" \
  --set tenantApi.image.tag="$VERSION" \
  --set ingestion.image.tag="$VERSION" \
  --set analysisEngine.image.tag="$VERSION" \
  --set tenantApi.image.pullPolicy=Never \
  --set ingestion.image.pullPolicy=Never \
  --set analysisEngine.image.pullPolicy=Never \
  --wait --timeout=120s

# ── Apply ClickHouse migrations ────────────────────────────────────────────────
log "Waiting for ClickHouse to be ready..."
kubectl wait --for=condition=ready pod \
  -l app=clickhouse -n "$NAMESPACE" --timeout=60s 2>/dev/null || warn "ClickHouse pod not ready yet — apply migrations manually"

log "Applying ClickHouse migrations..."
for f in clickhouse/migrations/V*.sql; do
  log "  Applying $f..."
  kubectl exec -n "$NAMESPACE" statefulset/clickhouse -- \
    clickhouse-client --multiquery < "$f" 2>/dev/null \
    || warn "Migration $f may already be applied — skipping"
done

# ── Verify ────────────────────────────────────────────────────────────────────
echo ""
log "Deployment complete. Pod status:"
kubectl get pods -n "$NAMESPACE"

echo ""
log "To access services (NodePort or port-forward):"
echo "  kubectl port-forward -n $NAMESPACE svc/tenant-api 8081:8081 &"
echo "  kubectl port-forward -n $NAMESPACE svc/ingestion  8082:8082 &"
echo "  kubectl port-forward -n $NAMESPACE svc/analysis-engine 8083:8083 &"
