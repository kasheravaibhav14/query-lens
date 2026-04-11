#!/bin/bash
# build-mac.sh — Build app OCI images on Mac, SCP tars to Pi, trigger load.
# Run from repo root on your MacBook Air.
#
# Usage:
#   ./scripts/build-mac.sh                          # uses default Pi address
#   ./scripts/build-mac.sh kashrpi@192.168.1.200    # override Pi address
#
# Requires: colima + docker  (brew install colima docker)
#   colima start             (run once per boot)

set -euo pipefail

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}[build-mac]${NC} $1"; }
warn() { echo -e "${YELLOW}[warn]     ${NC} $1"; }

PI_HOST="${1:-kashrpi@192.168.1.101}"
PI_IMAGES_DIR="/media/kashrpi/shared/query-lens-data/images"
LOCAL_IMAGES_DIR="$(mktemp -d)/query-lens-images"
mkdir -p "$LOCAL_IMAGES_DIR"

VERSION=0.0.1-SNAPSHOT
APP_MODULES=(tenant-api ingestion analysis-engine)

# ── Prereq check ──────────────────────────────────────────────────────────────
command -v docker &>/dev/null || { echo "docker not found. Run: brew install docker"; exit 1; }
docker info &>/dev/null      || { echo "Docker daemon not running. Run: colima start"; exit 1; }

# ── Build JARs ────────────────────────────────────────────────────────────────
log "Building all modules..."
set +u
# shellcheck source=/dev/null
source "$HOME/.sdkman/bin/sdkman-init.sh"
set -u
./gradlew build -x test

# ── Build OCI images targeting linux/arm64 ────────────────────────────────────
for module in "${APP_MODULES[@]}"; do
  IMAGE="query-lens/${module}:${VERSION}"
  TAR="$LOCAL_IMAGES_DIR/${module}.tar"

  log "Building OCI image for $module (linux/arm64)..."
  ./gradlew ":$module:bootBuildImage" --imagePlatform linux/arm64

  log "Saving $IMAGE → $TAR"
  docker save "$IMAGE" -o "$TAR"

  log "Removing local Docker copy to free space: $IMAGE"
  docker rmi "$IMAGE" 2>/dev/null || warn "Could not remove $IMAGE from Docker"
done

# ── SCP tars to Pi ────────────────────────────────────────────────────────────
log "Copying tars to ${PI_HOST}:${PI_IMAGES_DIR} ..."
ssh "$PI_HOST" "mkdir -p $PI_IMAGES_DIR"
scp "$LOCAL_IMAGES_DIR"/*.tar "${PI_HOST}:${PI_IMAGES_DIR}/"

log "Cleaning up local tars..."
rm -rf "$LOCAL_IMAGES_DIR"

# ── Trigger load script on Pi ─────────────────────────────────────────────────
log "Triggering load-images.sh on Pi..."
ssh "$PI_HOST" "cd ~/query-lens && bash scripts/load-images.sh"

log "Done."
