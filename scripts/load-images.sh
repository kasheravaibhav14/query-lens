#!/bin/bash
# load-images.sh — Import pre-built app image tars into k3s, run Helm, apply migrations.
# Run on the Pi, from the repo root. Triggered automatically by build-mac.sh.
#
# Usage:
#   ./scripts/load-images.sh

set -euo pipefail

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}[load-images]${NC} $1"; }
warn() { echo -e "${YELLOW}[warn]       ${NC} $1"; }

NAMESPACE=query-lens
CHART=./helm/query-lens
VERSION=0.0.1-SNAPSHOT
IMAGES_DIR="/media/kashrpi/shared/query-lens-data/images"
APP_MODULES=(tenant-api ingestion analysis-engine)

export KUBECONFIG="$HOME/.kube/config"

# ── Prereq check ──────────────────────────────────────────────────────────────
command -v k3s  &>/dev/null || { echo "k3s not found. Run: ./scripts/setup-linux.sh --k3s"; exit 1; }
command -v helm &>/dev/null || { echo "helm not found. Run: ./scripts/setup-linux.sh --k3s"; exit 1; }

# ── Import app image tars into k3s containerd ─────────────────────────────────
for module in "${APP_MODULES[@]}"; do
  TAR="$IMAGES_DIR/${module}.tar"
  if [[ ! -f "$TAR" ]]; then
    echo "ERROR: $TAR not found. Run build-mac.sh first."; exit 1
  fi
  log "Importing $module from $TAR..."
  sudo k3s ctr images import "$TAR"
done
log "All app images imported into k3s"

# ── Helm install / upgrade ────────────────────────────────────────────────────
kubectl create namespace "$NAMESPACE" 2>/dev/null || true

log "Running helm upgrade --install..."
helm upgrade --install query-lens "$CHART" \
  --namespace "$NAMESPACE" \
  -f "$CHART/values.yaml" \
  -f "$CHART/values-pi.yaml" \
  --set tenantApi.image.tag="$VERSION" \
  --set ingestion.image.tag="$VERSION" \
  --set analysisEngine.image.tag="$VERSION" \
  --wait --timeout=180s

# ── Apply ClickHouse DDL migrations ───────────────────────────────────────────
log "Waiting for ClickHouse to be ready..."
kubectl wait --for=condition=ready pod \
  -l app=clickhouse -n "$NAMESPACE" --timeout=90s 2>/dev/null \
  || warn "ClickHouse pod not ready — apply migrations manually if needed"

log "Applying ClickHouse migrations..."
for f in clickhouse/migrations/V*.sql; do
  log "  Applying $f..."
  kubectl exec -n "$NAMESPACE" statefulset/clickhouse -- \
    clickhouse-client --multiquery < "$f" 2>/dev/null \
    || warn "  $f may already be applied — skipping"
done

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
log "Deployment complete. Pod status:"
kubectl get pods -n "$NAMESPACE"

PI_IP=$(hostname -I | awk '{print $1}')
echo ""
log "Exposed API (port 80 via nginx-ingress):"
echo "  Add to /etc/hosts on your client:  $PI_IP  query-lens.local"
echo ""
echo "  POST http://query-lens.local/api/v1/tenants/register"
echo "  POST http://query-lens.local/ingest"
echo "  GET  http://query-lens.local/analyze?tenantId=<uuid>"
