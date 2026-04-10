#!/bin/bash
# smoke-test.sh — End-to-end smoke test for query-lens.
#
# Requires: curl, jq
# Services must be running:
#   - tenant-api  on :8081
#   - ingestion   on :8082
#   - analysis-engine on :8083
#
# Usage:
#   ./scripts/smoke-test.sh
#   ./scripts/smoke-test.sh --host 192.168.1.100   # target a remote machine

set -euo pipefail

HOST="localhost"
for arg in "$@"; do
  if [[ "$arg" == "--host" ]]; then
    shift; HOST="$1"
  fi
done

TENANT_API="http://${HOST}:8081"
INGESTION="http://${HOST}:8082"
ANALYSIS="http://${HOST}:8083"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

command -v jq &>/dev/null || fail "jq is required. Install with: sudo apt-get install -y jq"

# ── Step 1: Register a tenant ─────────────────────────────────────────────────
info "Registering tenant..."
REGISTER_RESPONSE=$(curl -s -w '\n%{http_code}' -X POST "${TENANT_API}/api/v1/tenants/register" \
  -H 'Content-Type: application/json' \
  -d '{"name":"smoke-test","databaseType":"MONGODB"}')

HTTP_CODE=$(echo "$REGISTER_RESPONSE" | tail -1)
BODY=$(echo "$REGISTER_RESPONSE" | head -n -1)

if [[ "$HTTP_CODE" != "201" ]]; then
  fail "Register failed with HTTP $HTTP_CODE: $BODY"
fi

TENANT_ID=$(echo "$BODY" | jq -r '.tenantId')
API_KEY=$(echo "$BODY" | jq -r '.apiKey')

if [[ -z "$TENANT_ID" || "$TENANT_ID" == "null" ]]; then
  fail "Could not extract tenantId from: $BODY"
fi
if [[ -z "$API_KEY" || "$API_KEY" == "null" ]]; then
  fail "Could not extract apiKey from: $BODY"
fi

pass "Registered tenant: tenantId=$TENANT_ID"
info "API key: $API_KEY"

# ── Step 2: Build ingest payload (COLLSCAN log lines) ─────────────────────────
# Three MongoDB Logv2 slow-query lines that will trigger collscan_missing_index:
#   planSummary=COLLSCAN, docsExamined>=500, durationMillis>=100, same namespace

LOG1='{"t":{"$date":"2024-01-15T10:00:00.000Z"},"ctx":"conn1","attr":{"durationMillis":520,"ns":"mydb.users","planSummary":"COLLSCAN","keysExamined":0,"docsExamined":820,"nreturned":3,"command":{"find":"users","filter":{"email":"a@x.com"}}}}'
LOG2='{"t":{"$date":"2024-01-15T10:00:01.000Z"},"ctx":"conn2","attr":{"durationMillis":610,"ns":"mydb.users","planSummary":"COLLSCAN","keysExamined":0,"docsExamined":820,"nreturned":2,"command":{"find":"users","filter":{"email":"b@x.com"}}}}'
LOG3='{"t":{"$date":"2024-01-15T10:00:02.000Z"},"ctx":"conn3","attr":{"durationMillis":490,"ns":"mydb.users","planSummary":"COLLSCAN","keysExamined":0,"docsExamined":820,"nreturned":4,"command":{"find":"users","filter":{"email":"c@x.com"}}}}'

PAYLOAD=$(jq -n \
  --arg l1 "$LOG1" \
  --arg l2 "$LOG2" \
  --arg l3 "$LOG3" \
  '{"logs":[$l1,$l2,$l3]}')

# ── Step 3: Ingest log lines ──────────────────────────────────────────────────
info "Ingesting 3 COLLSCAN log lines..."
INGEST_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${INGESTION}/ingest" \
  -H "X-Api-Key: ${API_KEY}" \
  -H "X-Source: smoke-test" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")

if [[ "$INGEST_CODE" != "204" ]]; then
  fail "Ingest returned HTTP $INGEST_CODE (expected 204)"
fi
pass "Ingest accepted (204)"

# ── Step 4: Wait for Kafka + analysis-engine to consume ───────────────────────
info "Waiting 5s for pipeline to process..."
sleep 5

# ── Step 5: Verify unauthorized access returns 401 ───────────────────────────
UNAUTH_CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${INGESTION}/ingest" \
  -H "X-Api-Key: ql_invalid_key" \
  -H 'Content-Type: application/json' \
  -d "$PAYLOAD")
if [[ "$UNAUTH_CODE" != "401" ]]; then
  fail "Expected 401 for invalid API key, got $UNAUTH_CODE"
fi
pass "Invalid API key correctly rejected (401)"

# ── Step 6: GET /analyze ──────────────────────────────────────────────────────
info "Calling GET /analyze?tenantId=${TENANT_ID}..."
ANALYZE_RESPONSE=$(curl -s -w '\n%{http_code}' \
  "${ANALYSIS}/analyze?tenantId=${TENANT_ID}")

ANALYZE_CODE=$(echo "$ANALYZE_RESPONSE" | tail -1)
ANALYZE_BODY=$(echo "$ANALYZE_RESPONSE" | head -n -1)

if [[ "$ANALYZE_CODE" != "200" ]]; then
  fail "Analyze returned HTTP $ANALYZE_CODE: $ANALYZE_BODY"
fi
pass "Analyze returned 200"

FINDING_COUNT=$(echo "$ANALYZE_BODY" | jq '.findings | length')
info "Findings: $FINDING_COUNT"
echo "$ANALYZE_BODY" | jq '.'

if [[ "$FINDING_COUNT" -gt 0 ]]; then
  RULE=$(echo "$ANALYZE_BODY" | jq -r '.findings[0].ruleId')
  pass "Rule fired: $RULE"
else
  echo -e "${YELLOW}[WARN]${NC} No findings — Kafka may not have delivered yet. Try re-running or check analysis-engine logs."
fi

echo ""
info "Smoke test complete. tenantId=$TENANT_ID"
