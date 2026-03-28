#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# Spin up a local Postgres container via Docker
# Works on macOS and Linux. Codebase-agnostic.
#
# Usage:
#   ./scripts/postgres-local.sh              # use defaults
#   PG_DB=mydb ./scripts/postgres-local.sh  # override db name
#
# Env vars (all optional):
#   PG_USER      default: postgres
#   PG_PASSWORD  default: postgres
#   PG_DB        default: postgres
#   PG_PORT      default: 5432
#   PG_VERSION   default: 16-alpine
#   CONTAINER    default: postgres-local
# ---------------------------------------------------------------------------

PG_USER="${PG_USER:-postgres}"
PG_PASSWORD="${PG_PASSWORD:-postgres}"
PG_DB="${PG_DB:-postgres}"
PG_PORT="${PG_PORT:-5432}"
PG_VERSION="${PG_VERSION:-16-alpine}"
CONTAINER="${CONTAINER:-postgres-local}"

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
    echo "Postgres container '$CONTAINER' is already running."
    ;;
  exited|paused)
    echo "Restarting existing container '$CONTAINER'..."
    docker start "$CONTAINER"
    ;;
  missing)
    echo "Starting new Postgres $PG_VERSION container..."
    docker run -d \
      --name "$CONTAINER" \
      -e POSTGRES_USER="$PG_USER" \
      -e POSTGRES_PASSWORD="$PG_PASSWORD" \
      -e POSTGRES_DB="$PG_DB" \
      -p "${PG_PORT}:5432" \
      "postgres:${PG_VERSION}"
    ;;
  *)
    echo "ERROR: Container '$CONTAINER' is in unexpected state: $STATUS"
    exit 1
    ;;
esac

# -- Wait for Postgres to be ready --
echo "Waiting for Postgres to be ready..."
for i in $(seq 1 20); do
  if docker exec "$CONTAINER" pg_isready -U "$PG_USER" -d "$PG_DB" &>/dev/null; then
    echo "Postgres is ready."
    break
  fi
  if [ "$i" -eq 20 ]; then
    echo "ERROR: Postgres did not become ready in time."
    docker logs "$CONTAINER" --tail 20
    exit 1
  fi
  sleep 1
done

# -- Print connection details --
echo ""
echo "Connection string: postgresql://${PG_USER}:${PG_PASSWORD}@localhost:${PG_PORT}/${PG_DB}"
echo "JDBC URL:          jdbc:postgresql://localhost:${PG_PORT}/${PG_DB}"
echo ""
echo "To stop:  docker stop ${CONTAINER}"
echo "To reset: docker rm -f ${CONTAINER}"
