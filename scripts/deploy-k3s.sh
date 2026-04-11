#!/bin/bash
# deploy-k3s.sh — Build images and deploy query-lens to k3s via Helm.
# Run this from the repo root AFTER setup-linux.sh --k3s has completed.
#
# How images work without an external registry:
#   k3s uses containerd, not the Docker daemon. This script:
#     1. Builds JARs with Gradle
#     2. Builds OCI images via Cloud Native Buildpacks (bootBuildImage)
#     3. Imports infrastructure images (postgres, kafka, clickhouse) from
#        Docker into k3s containerd, then removes the Docker copies to
#        recover disk space (root FS is tight — data lives on shared drive)
#     4. Imports app images into k3s containerd
#     5. Helm upgrade --install with values-pi.yaml overrides
#     6. Applies ClickHouse DDL migrations
#
# Re-run after every code change to rebuild + redeploy.

set -euo pipefail

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}[deploy]${NC} $1"; }
warn() { echo -e "${YELLOW}[warn]  ${NC} $1"; }

NAMESPACE=query-lens
CHART=./helm/query-lens
VERSION=0.0.1-SNAPSHOT   # matches version in build.gradle files
SHARED_PATH="/media/kashrpi/shared/query-lens-data"
IMAGES_DIR="$SHARED_PATH/images"
export GRADLE_USER_HOME="$SHARED_PATH/gradle"

# ── Prereq check ──────────────────────────────────────────────────────────────
command -v k3s  &>/dev/null || { echo "k3s not found. Run: ./scripts/setup-linux.sh --k3s"; exit 1; }
command -v helm &>/dev/null || { echo "helm not found. Run: ./scripts/setup-linux.sh --k3s"; exit 1; }
export KUBECONFIG="$HOME/.kube/config"

# ── Build JARs ────────────────────────────────────────────────────────────────
log "Building all modules..."
set +u
# shellcheck source=/dev/null
source "$HOME/.sdkman/bin/sdkman-init.sh"
set -u
./gradlew build -x test

# ── Build OCI images (Cloud Native Buildpacks — no Dockerfile needed) ─────────
APP_MODULES=(tenant-api ingestion analysis-engine)
for module in "${APP_MODULES[@]}"; do
  log "Building OCI image for $module..."
  ./gradlew ":$module:bootBuildImage"
  IMAGE="query-lens/${module}:${VERSION}"
  TAR="$IMAGES_DIR/${module}.tar"
  log "Saving $IMAGE → $TAR"
  docker save "$IMAGE" -o "$TAR"
  log "Removing Docker copy to free root-FS space: $IMAGE"
  docker rmi "$IMAGE" 2>/dev/null || warn "Could not remove $IMAGE from Docker"
done

# ── Import infrastructure images into k3s containerd ─────────────────────────
# These images are needed by the Helm chart but never built by Gradle.
# We pipe directly from Docker to avoid a registry, then remove the Docker
# copy to recover ~1.8 GB from the root filesystem.
declare -A INFRA_IMAGES=(
  ["postgres:16"]="postgres:16"
  ["apache/kafka:3.9.0"]="apache/kafka:3.9.0"
  ["clickhouse/clickhouse-server:24.3"]="clickhouse/clickhouse-server:24.3"
)

for docker_tag in "${!INFRA_IMAGES[@]}"; do
  safe_name="${docker_tag//\//_}"
  safe_name="${safe_name//:/_}"
  TAR="$IMAGES_DIR/${safe_name}.tar"
  if sudo k3s ctr images ls 2>/dev/null | grep -qF "${docker_tag##*/}" ; then
    warn "Already in k3s containerd: $docker_tag — skipping import"
  else
    log "Pulling $docker_tag..."
    docker pull "$docker_tag" 2>/dev/null || true
    log "Saving $docker_tag → $TAR"
    docker save "$docker_tag" -o "$TAR"
    log "Importing into k3s containerd from $TAR..."
    sudo k3s ctr images import "$TAR"
    log "Removing Docker copy to free root-FS space: $docker_tag"
    docker rmi "$docker_tag" 2>/dev/null \
      || warn "Could not remove $docker_tag from Docker (may be referenced elsewhere)"
  fi
done
log "Infrastructure images ready in k3s containerd"

# ── Import app images into k3s containerd ─────────────────────────────────────
for module in "${APP_MODULES[@]}"; do
  TAR="$IMAGES_DIR/${module}.tar"
  log "Importing $module image into k3s from $TAR..."
  sudo k3s ctr images import "$TAR"
done
log "All app images imported"

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
echo ""
log "Quick port-forward alternative (no /etc/hosts needed):"
echo "  kubectl port-forward -n $NAMESPACE svc/tenant-api      8081:8081 &"
echo "  kubectl port-forward -n $NAMESPACE svc/ingestion       8082:8082 &"
echo "  kubectl port-forward -n $NAMESPACE svc/analysis-engine 8083:8083 &"
