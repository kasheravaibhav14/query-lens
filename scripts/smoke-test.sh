#!/bin/bash
# smoke-test.sh — End-to-end smoke test for query-lens.
#
# Covers three rule scenarios and two auth checks:
#   Scenario 1: collscan_missing_index  — 3 COLLSCAN logs on mydb.users
#   Scenario 2: poor_index_selectivity — 2 IXSCAN logs on mydb.orders (500 keys, 1 returned)
#   Scenario 3: retry_storm            — 10 rapid queries on mydb.sessions
#   Auth:        invalid key → 401, missing key → 401
#
# Requires: curl, jq
# Usage:
#   ./scripts/smoke-test.sh
#   ./scripts/smoke-test.sh --host 192.168.1.101

set -euo pipefail

# ── Args ───────────────────────────────────────────────────────────────────────
HOST="localhost"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host) HOST="$2"; shift 2 ;;
    *) shift ;;
  esac
done

TENANT_API="http://${HOST}:8081"
INGESTION="http://${HOST}:8082"
ANALYSIS="http://${HOST}:8083"

# ── Output helpers ─────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
PASS_COUNT=0; FAIL_COUNT=0

pass() { echo -e "${GREEN}[PASS]${NC} $1"; (( PASS_COUNT++ )) || true; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; (( FAIL_COUNT++ )) || true; }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

command -v jq &>/dev/null || { echo "jq is required: sudo apt-get install -y jq"; exit 1; }

# ── Readiness check ────────────────────────────────────────────────────────────
wait_for_service() {
  local name="$1" url="$2"
  info "Waiting for $name..."
  for _ in $(seq 1 30); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$url" 2>/dev/null || echo "000")
    if [[ "$code" != "000" ]]; then
      info "$name is up (HTTP $code)"
      return 0
    fi
    sleep 2
  done
  echo -e "${RED}[FAIL]${NC} $name did not respond within 60s at $url"
  exit 1
}

wait_for_service "tenant-api"      "${TENANT_API}/api/v1/tenants"
wait_for_service "ingestion"       "${INGESTION}/ingest"
wait_for_service "analysis-engine" "${ANALYSIS}/analyze?tenantId=probe"

# ── Register tenant ────────────────────────────────────────────────────────────
info "Registering tenant..."
REGISTER_RESPONSE=$(curl -s -w '\n%{http_code}' -X POST "${TENANT_API}/api/v1/tenants/register" \
  -H 'Content-Type: application/json' \
  -d '{"name":"smoke-test","databaseType":"MONGODB"}')

HTTP_CODE=$(echo "$REGISTER_RESPONSE" | tail -1)
BODY=$(echo "$REGISTER_RESPONSE" | head -n -1)

if [[ "$HTTP_CODE" != "201" ]]; then
  echo -e "${RED}[FAIL]${NC} Register failed HTTP $HTTP_CODE: $BODY"
  exit 1
fi

TENANT_ID=$(echo "$BODY" | jq -r '.tenantId')
API_KEY=$(echo "$BODY" | jq -r '.apiKey')

if [[ -z "$TENANT_ID" || "$TENANT_ID" == "null" ]]; then
  echo -e "${RED}[FAIL]${NC} No tenantId in: $BODY"; exit 1
fi
if [[ -z "$API_KEY" || "$API_KEY" == "null" ]]; then
  echo -e "${RED}[FAIL]${NC} No apiKey in: $BODY"; exit 1
fi

pass "Registered tenant: tenantId=$TENANT_ID"

# ── Auth checks ────────────────────────────────────────────────────────────────
DUMMY='{"logs":["{}"]}'

code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${INGESTION}/ingest" \
  -H "X-Api-Key: ql_invalid_key" -H 'Content-Type: application/json' -d "$DUMMY")
[[ "$code" == "401" ]] && pass "Invalid API key → 401" || fail "Expected 401 for invalid key, got $code"

code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${INGESTION}/ingest" \
  -H 'Content-Type: application/json' -d "$DUMMY")
[[ "$code" == "401" ]] && pass "Missing API key → 401" || fail "Expected 401 for missing key, got $code"

# ── Poll helper ────────────────────────────────────────────────────────────────
# Polls /analyze until ruleId appears or timeout (seconds) expires.
# Prints the response body on success so the caller can inspect it.
poll_for_rule() {
  local rule_id="$1" tid="$2" timeout="${3:-30}"
  for _ in $(seq 1 $(( timeout / 2 ))); do
    body=$(curl -s "${ANALYSIS}/analyze?tenantId=${tid}")
    match=$(echo "$body" | jq -r --arg r "$rule_id" '.findings[]? | select(.ruleId == $r) | .ruleId' | head -1)
    if [[ "$match" == "$rule_id" ]]; then
      echo "$body"
      return 0
    fi
    sleep 2
  done
  return 1
}

ingest() {
  local payload="$1"
  curl -s -o /dev/null -w '%{http_code}' -X POST "${INGESTION}/ingest" \
    -H "X-Api-Key: ${API_KEY}" \
    -H "X-Source: smoke-test" \
    -H 'Content-Type: application/json' \
    -d "$payload"
}

# ── Scenario 1: collscan_missing_index ─────────────────────────────────────────
# Trigger: COLLSCAN, docsExamined >= 500, durationMillis >= 100, >= 2 occurrences on same ns
echo ""
info "=== Scenario 1: collscan_missing_index ==="

make_collscan() {
  printf '{"t":{"$date":"%s"},"ctx":"%s","attr":{"durationMillis":520,"ns":"mydb.users","planSummary":"COLLSCAN","keysExamined":0,"docsExamined":820,"nreturned":3,"command":{"find":"users","filter":{"email":"%s"}}}}' "$1" "$2" "$3"
}

S1_PAYLOAD=$(jq -n \
  --arg l1 "$(make_collscan "2024-01-15T10:00:00.000Z" "conn1" "a@x.com")" \
  --arg l2 "$(make_collscan "2024-01-15T10:00:01.000Z" "conn2" "b@x.com")" \
  --arg l3 "$(make_collscan "2024-01-15T10:00:02.000Z" "conn3" "c@x.com")" \
  '{"logs":[$l1,$l2,$l3]}')

code=$(ingest "$S1_PAYLOAD")
[[ "$code" == "204" ]] && pass "Ingest accepted (204)" || fail "Ingest returned $code (expected 204)"

info "Polling for collscan_missing_index (up to 30s)..."
if result=$(poll_for_rule "collscan_missing_index" "$TENANT_ID" 30); then
  pass "Rule fired: collscan_missing_index"
  ns=$(echo "$result"     | jq -r '.findings[] | select(.ruleId=="collscan_missing_index") | .namespace')
  avg=$(echo "$result"    | jq -r '.findings[] | select(.ruleId=="collscan_missing_index") | .details.avgDurationMillis')
  pass "  namespace=$ns  avgDurationMillis=$avg"
else
  fail "collscan_missing_index not found within 30s"
fi

# ── Scenario 2: poor_index_selectivity ────────────────────────────────────────
# Trigger: IXSCAN, keysExamined/nreturned >= 50, durationMillis >= 100, >= 2 occurrences
echo ""
info "=== Scenario 2: poor_index_selectivity ==="

make_ixscan() {
  printf '{"t":{"$date":"%s"},"ctx":"%s","attr":{"durationMillis":350,"ns":"mydb.orders","planSummary":"IXSCAN","keysExamined":500,"docsExamined":1,"nreturned":1,"command":{"find":"orders","filter":{"status":"pending"}}}}' "$1" "$2"
}

S2_PAYLOAD=$(jq -n \
  --arg l1 "$(make_ixscan "2024-01-15T10:01:00.000Z" "conn4")" \
  --arg l2 "$(make_ixscan "2024-01-15T10:01:01.000Z" "conn5")" \
  '{"logs":[$l1,$l2]}')

code=$(ingest "$S2_PAYLOAD")
[[ "$code" == "204" ]] && pass "Ingest accepted (204)" || fail "Ingest returned $code (expected 204)"

info "Polling for poor_index_selectivity (up to 30s)..."
if result=$(poll_for_rule "poor_index_selectivity" "$TENANT_ID" 30); then
  pass "Rule fired: poor_index_selectivity"
  ns=$(echo "$result"    | jq -r '.findings[] | select(.ruleId=="poor_index_selectivity") | .namespace')
  ratio=$(echo "$result" | jq -r '.findings[] | select(.ruleId=="poor_index_selectivity") | .details.avgSelectivityRatio')
  pass "  namespace=$ns  keysExamined/nreturned ratio=$ratio"
else
  fail "poor_index_selectivity not found within 30s"
fi

# ── Scenario 3: retry_storm ────────────────────────────────────────────────────
# Trigger: >= 10 queries on the same namespace in the window, any plan
echo ""
info "=== Scenario 3: retry_storm ==="

S3_PAYLOAD='{"logs":[]}'
for i in $(seq 0 9); do
  LOG=$(printf '{"t":{"$date":"2024-01-15T10:02:%02dZ"},"ctx":"conn%d","attr":{"durationMillis":12,"ns":"mydb.sessions","planSummary":"IXSCAN","keysExamined":1,"docsExamined":1,"nreturned":1,"command":{"find":"sessions","filter":{"token":"abc123"}}}}' "$i" "$i")
  S3_PAYLOAD=$(echo "$S3_PAYLOAD" | jq --arg l "$LOG" '.logs += [$l]')
done

code=$(ingest "$S3_PAYLOAD")
[[ "$code" == "204" ]] && pass "Ingest accepted (204)" || fail "Ingest returned $code (expected 204)"

info "Polling for retry_storm (up to 30s)..."
if result=$(poll_for_rule "retry_storm" "$TENANT_ID" 30); then
  pass "Rule fired: retry_storm"
  ns=$(echo "$result")
  count=$(echo "$result" | jq -r '.findings[] | select(.ruleId=="retry_storm") | .occurrenceCount')
  pass "  namespace=mydb.sessions  occurrenceCount=$count"
else
  fail "retry_storm not found within 30s"
fi

# ── Summary ────────────────────────────────────────────────────────────────────
echo ""
info "Smoke test complete. tenantId=$TENANT_ID"
echo -e "  ${GREEN}PASS: $PASS_COUNT${NC}   ${RED}FAIL: $FAIL_COUNT${NC}"
echo ""

[[ "$FAIL_COUNT" -eq 0 ]] || exit 1
