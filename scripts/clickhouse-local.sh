#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# Spin up a local ClickHouse container via Docker
# Works on macOS and Linux. Codebase-agnostic.
#
# Usage:
#   ./scripts/clickhouse-local.sh              # use defaults
#   CH_PASSWORD=secret ./scripts/clickhouse-local.sh
#
# Env vars (all optional):
#   CH_HOST       default: localhost
#   CH_HTTP_PORT  default: 8123
#   CH_TCP_PORT   default: 9000
#   CH_PASSWORD   default: (empty — ClickHouse default)
#   CH_VERSION    default: 24.3
#   CONTAINER     default: clickhouse-local
# ---------------------------------------------------------------------------

CH_HOST="${CH_HOST:-localhost}"
CH_HTTP_PORT="${CH_HTTP_PORT:-8123}"
CH_TCP_PORT="${CH_TCP_PORT:-9000}"
CH_PASSWORD="${CH_PASSWORD:-}"
CH_VERSION="${CH_VERSION:-24.3}"
CONTAINER="${CONTAINER:-clickhouse-local}"

# -- Check Docker is available and running --
if ! command -v docker &>/dev/null; then
  echo "ERROR: docker not found. Install OrbStack or Docker Desktop first."
  exit 1
fi

if ! docker info &>/dev/null; then
  echo "ERROR: Docker daemon is not running. Start OrbStack or Docker Desktop."
  exit 1
fi

# -- Idempotent start logic --
if ! docker inspect "$CONTAINER" &>/dev/null; then
  STATUS="missing"
else
  STATUS=$(docker inspect -f '{{.State.Status}}' "$CONTAINER")
fi

case "$STATUS" in
  running)
    echo "ClickHouse container '$CONTAINER' is already running."
    ;;
  exited|paused)
    echo "Restarting existing container '$CONTAINER'..."
    docker start "$CONTAINER"
    ;;
  missing)
    echo "Starting new ClickHouse ${CH_VERSION} container..."
    docker run -d \
      --name "$CONTAINER" \
      -p "${CH_HTTP_PORT}:8123" \
      -p "${CH_TCP_PORT}:9000" \
      -e CLICKHOUSE_PASSWORD="${CH_PASSWORD}" \
      "clickhouse/clickhouse-server:${CH_VERSION}"
    ;;
  *)
    echo "ERROR: Container '$CONTAINER' is in unexpected state: $STATUS"
    exit 1
    ;;
esac

# -- Wait for ClickHouse to be ready --
echo "Waiting for ClickHouse to be ready..."
for i in $(seq 1 30); do
  if docker exec "$CONTAINER" clickhouse-client --query "SELECT 1" &>/dev/null; then
    echo "ClickHouse is ready."
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "ERROR: ClickHouse did not become ready in time."
    docker logs "$CONTAINER" --tail 20
    exit 1
  fi
  sleep 1
done

# -- Print connection details --
echo ""
echo "HTTP interface: http://${CH_HOST}:${CH_HTTP_PORT}"
echo "Native TCP:     ${CH_HOST}:${CH_TCP_PORT}"
echo "JDBC URL:       jdbc:clickhouse://${CH_HOST}:${CH_HTTP_PORT}/default"
echo ""
echo "To stop:  docker stop ${CONTAINER}"
echo "To reset: docker rm -f ${CONTAINER}"
