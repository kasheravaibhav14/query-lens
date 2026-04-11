#!/bin/bash
# setup-linux.sh — Bootstrap a fresh 64-bit Linux machine (Raspberry Pi OS, Ubuntu, Debian)
# to run query-lens via Docker Compose or k3s (lightweight Kubernetes).
#
# Requirements:
#   - 64-bit OS (aarch64 / x86_64) — ClickHouse and Java 25 do NOT support 32-bit ARM
#   - Raspberry Pi 4 / 5 with >= 4 GB RAM recommended (ClickHouse is memory-hungry)
#   - Internet access
#
# Usage:
#   chmod +x scripts/setup-linux.sh
#   ./scripts/setup-linux.sh              # Docker + Java only (docker-compose path)
#   ./scripts/setup-linux.sh --k3s        # Also installs k3s + Helm (Kubernetes path)

set -euo pipefail

# ── Helpers ───────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${GREEN}[setup]${NC} $1"; }
warn() { echo -e "${YELLOW}[warn] ${NC} $1"; }
err()  { echo -e "${RED}[error]${NC} $1"; exit 1; }

INSTALL_K3S=false
for arg in "$@"; do [[ "$arg" == "--k3s" ]] && INSTALL_K3S=true; done

# ── Architecture check ────────────────────────────────────────────────────────
ARCH=$(uname -m)
if [[ "$ARCH" != "aarch64" && "$ARCH" != "x86_64" ]]; then
  err "Unsupported architecture: $ARCH. 64-bit (aarch64 or x86_64) is required."
fi
log "Architecture: $ARCH — OK"

# ── System update ─────────────────────────────────────────────────────────────
log "Updating system packages..."
sudo apt-get update -qq
sudo apt-get install -y -qq curl git

# ── Docker ────────────────────────────────────────────────────────────────────
if command -v docker &>/dev/null; then
  warn "Docker already installed: $(docker --version)"
else
  log "Installing Docker..."
  curl -fsSL https://get.docker.com | sh
  sudo usermod -aG docker "$USER"
  warn "Added $USER to 'docker' group. Re-login or run: newgrp docker"
fi

# Note: Docker data-root cannot be moved to the shared drive — it is exFAT,
# which does not support Linux file ownership (chown). Docker stays on the SD card.
# The deploy script removes app images from Docker immediately after saving them
# to the shared drive, so Docker's footprint stays small.

# Ensure Docker daemon is running
sudo systemctl enable --now docker

# ── Java 25 via SDKMAN ────────────────────────────────────────────────────────
if [ ! -d "$HOME/.sdkman" ]; then
  log "Installing SDKMAN..."
  curl -s "https://get.sdkman.io" | bash
fi

# SDKMAN uses unbound variables throughout — disable -u for the entire SDKMAN block
set +u
# shellcheck source=/dev/null
source "$HOME/.sdkman/bin/sdkman-init.sh"

if ! sdk list java | grep -q "25.*tem.*installed"; then
  log "Installing Java 25 (Temurin)..."
  sdk install java 25-tem
else
  warn "Java 25 already installed"
fi
sdk default java 25-tem
set -u
log "Java: $(java -version 2>&1 | head -1)"

# ── k3s + Helm (optional) ─────────────────────────────────────────────────────
if [[ "$INSTALL_K3S" == "true" ]]; then

  # ── k3s ─────────────────────────────────────────────────────────────────────
  if command -v k3s &>/dev/null; then
    warn "k3s already installed: $(k3s --version | head -1)"
  else
    log "Installing k3s (Traefik disabled — nginx-ingress used instead)..."
    # Traefik is disabled: the Helm chart uses nginx ingress annotations which
    # are not compatible with Traefik. nginx-ingress is installed below.
    # --data-dir points k3s's containerd image store, PV storage, and pod logs
    # at the shared drive so the SD card is never filled by k3s.
    curl -sfL https://get.k3s.io | INSTALL_K3S_EXEC="--disable=traefik --data-dir=$SHARED_PATH/k3s" sh -

    # Allow current user to run kubectl without sudo
    sudo mkdir -p "$HOME/.kube"
    sudo cp /etc/rancher/k3s/k3s.yaml "$HOME/.kube/config"
    sudo chown "$USER:$USER" "$HOME/.kube/config"
    export KUBECONFIG="$HOME/.kube/config"
    # Persist for future shells
    grep -qxF 'export KUBECONFIG=$HOME/.kube/config' "$HOME/.bashrc" \
      || echo 'export KUBECONFIG=$HOME/.kube/config' >> "$HOME/.bashrc"
    grep -qxF 'export KUBECONFIG=$HOME/.kube/config' "$HOME/.zshrc" 2>/dev/null \
      || echo 'export KUBECONFIG=$HOME/.kube/config' >> "$HOME/.zshrc"

    log "k3s installed. Waiting for node to be ready..."
    kubectl wait --for=condition=ready node --all --timeout=90s
  fi

  export KUBECONFIG="$HOME/.kube/config"

  # ── Helm ─────────────────────────────────────────────────────────────────────
  if command -v helm &>/dev/null; then
    warn "Helm already installed: $(helm version --short)"
  else
    log "Installing Helm..."
    curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
    log "Helm: $(helm version --short)"
  fi

  # ── nginx-ingress controller ──────────────────────────────────────────────────
  # Runs as a DaemonSet with hostNetwork=true so it binds port 80 on the Pi's IP.
  # This is what makes http://query-lens.local work from any machine on the LAN.
  if helm status ingress-nginx -n ingress-nginx &>/dev/null; then
    warn "nginx-ingress already installed"
  else
    log "Installing nginx-ingress controller..."
    helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx 2>/dev/null || true
    helm repo update ingress-nginx
    helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
      --namespace ingress-nginx --create-namespace \
      --set controller.hostNetwork=true \
      --set controller.kind=DaemonSet \
      --set controller.service.type=ClusterIP \
      --wait --timeout=120s
    log "nginx-ingress installed"
  fi

  # ── Configure local-path provisioner to use shared drive ─────────────────────
  # k3s ships with a local-path StorageClass. By default it stores PVs under
  # /var/lib/rancher/k3s/storage — the root filesystem (only 58GB, 90% full).
  # We redirect it to the 373GB external drive at /media/kashrpi/shared.
  SHARED_PATH="/media/kashrpi/shared/query-lens-data"
  log "Configuring local-path provisioner → $SHARED_PATH"
  sudo mkdir -p "$SHARED_PATH/images"   # OCI image tarballs — app + infra
  sudo mkdir -p "$SHARED_PATH/gradle"   # Gradle user home (caches, wrapper, daemon)
  sudo chown -R kashrpi:kashrpi "$SHARED_PATH"

  # Redirect Gradle's user home to the shared drive so caches don't fill the SD card.
  grep -qxF "export GRADLE_USER_HOME=$SHARED_PATH/gradle" "$HOME/.bashrc" \
    || echo "export GRADLE_USER_HOME=$SHARED_PATH/gradle" >> "$HOME/.bashrc"
  grep -qxF "export GRADLE_USER_HOME=$SHARED_PATH/gradle" "$HOME/.zshrc" 2>/dev/null \
    || echo "export GRADLE_USER_HOME=$SHARED_PATH/gradle" >> "$HOME/.zshrc"
  export GRADLE_USER_HOME="$SHARED_PATH/gradle"
  log "GRADLE_USER_HOME → $GRADLE_USER_HOME"

  # Patch the provisioner's ConfigMap so all new PVCs land on the shared drive.
  kubectl patch configmap local-path-config -n kube-system --type merge \
    -p "{\"data\":{\"config.json\":\"{\\\"nodePathMap\\\":[{\\\"node\\\":\\\"DEFAULT_PATH_FOR_NON_LISTED_NODES\\\",\\\"paths\\\":[\\\"$SHARED_PATH\\\"]}]}\"}}"

  # Restart the provisioner to pick up the new path.
  kubectl rollout restart deployment/local-path-provisioner -n kube-system
  kubectl rollout status  deployment/local-path-provisioner -n kube-system --timeout=60s
  log "local-path provisioner now using $SHARED_PATH"

fi

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
log "Setup complete. Next steps:"
echo ""
echo "  1. Clone the repo (if not already done):"
echo "     git clone <repo-url> query-lens && cd query-lens"
echo ""
if [[ "$INSTALL_K3S" == "true" ]]; then
echo "  2. Reload your shell so kubectl + KUBECONFIG are active:"
echo "     source ~/.zshrc   # or ~/.bashrc"
echo ""
echo "  3. Build images and deploy:"
echo "     ./scripts/deploy-k3s.sh"
else
echo "  2. Start infrastructure and services:"
echo "     docker compose up -d"
echo "     source \$HOME/.sdkman/bin/sdkman-init.sh"
echo "     ./gradlew build -x test"
echo "     # See README.md — 'Deploy on a Linux Server' section"
fi
echo ""
warn "If you were added to the 'docker' group, run: newgrp docker  (or re-login)"
