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
  if command -v k3s &>/dev/null; then
    warn "k3s already installed: $(k3s --version | head -1)"
  else
    log "Installing k3s (lightweight Kubernetes)..."
    curl -sfL https://get.k3s.io | sh -

    # Allow current user to run kubectl without sudo
    sudo mkdir -p "$HOME/.kube"
    sudo cp /etc/rancher/k3s/k3s.yaml "$HOME/.kube/config"
    sudo chown "$USER:$USER" "$HOME/.kube/config"
    echo 'export KUBECONFIG=$HOME/.kube/config' >> "$HOME/.bashrc"
    export KUBECONFIG="$HOME/.kube/config"
    log "k3s installed. Waiting for node to be ready..."
    kubectl wait --for=condition=ready node --all --timeout=60s
  fi

  if command -v helm &>/dev/null; then
    warn "Helm already installed: $(helm version --short)"
  else
    log "Installing Helm..."
    curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
    log "Helm: $(helm version --short)"
  fi

  # ── Local image registry for k3s ────────────────────────────────────────────
  # k3s uses containerd, not Docker. Images built with Docker must be imported.
  # Option A (used by deploy-linux.sh): docker save | k3s ctr images import
  # Option B (set up once here): local registry on :5000
  log "Setting up local Docker registry on :5000 for k3s..."
  docker run -d --name registry --restart=always -p 5000:5000 registry:2 2>/dev/null \
    || warn "Registry already running"

  # Tell k3s to trust the local insecure registry
  sudo mkdir -p /etc/rancher/k3s
  cat <<EOF | sudo tee /etc/rancher/k3s/registries.yaml
mirrors:
  "localhost:5000":
    endpoint:
      - "http://localhost:5000"
EOF
  sudo systemctl restart k3s
  log "k3s configured to pull from localhost:5000"
fi

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
log "Setup complete. Next steps:"
echo ""
echo "  1. Clone the repo (if not already done):"
echo "     git clone <repo-url> query-lens && cd query-lens"
echo ""
if [[ "$INSTALL_K3S" == "true" ]]; then
echo "  2. Build and deploy via Kubernetes (Helm):"
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
