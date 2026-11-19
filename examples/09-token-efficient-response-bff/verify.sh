#!/usr/bin/env bash
# Starts the stack with the token-efficient-response-bff app, checks that the agent BFF
# projection omits the full payload's heavy and internal fields and carries no raw UUID, that
# the read-only GraphQL surface returns exactly the fields a query selects, and measures the
# byte contrast between the full payload and both lean surfaces.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
CONTRACT_FILE=../../contracts/09-order-bff.graphql
TOTAL=0
PASSED=0
TMP_FILES=()

cleanup() {
    rm -f "${TMP_FILES[@]}" 2>/dev/null || true
    if [[ "${KEEP:-0}" == "1" ]]; then
        echo "KEEP=1: stack left running; run \`docker compose -f compose.yaml down -v\` in this directory to remove it"
    else
        "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

check() { # check "<description>" <command...>
    local desc="$1"; shift
    TOTAL=$((TOTAL + 1))
    if "$@" >/dev/null 2>&1; then
        PASSED=$((PASSED + 1)); printf 'PASS %d: %s\n' "$TOTAL" "$desc"
    else
        printf 'FAIL %d: %s\n' "$TOTAL" "$desc"
    fi
}

healthy() { [[ "$(docker inspect -f '{{.State.Health.Status}}' "$1")" == "healthy" ]]; }

new_tmp_file() { local f; f=$(mktemp); TMP_FILES+=("$f"); printf '%s' "$f"; }

UUID_RE='[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'

full_payload_carries_heavy_fields() {
    local body
    body=$(curl -sf "$APP/orders/O-1001") || return 1
    echo "$body" | jq -e 'has("internalNotes") and has("auditTrail") and (.customer.customerUuid | length) > 0' >/dev/null
}

bff_projection_omits_heavy_fields() {
    local body
    body=$(curl -sf "$APP/orders/O-1001/summary") || return 1
    echo "$body" | jq -e '(has("internalNotes") or has("auditTrail") or has("customer") or has("recordUuid")) | not' >/dev/null
}

bff_projection_carries_human_id_no_raw_uuid() {
    local body
    body=$(curl -sf "$APP/orders/O-1001/summary") || return 1
    [[ "$(echo "$body" | jq -r '.orderId')" == "O-1001" ]] || return 1
    ! echo "$body" | grep -qE "$UUID_RE"
}

graphql_query() { # graphql_query <query>
    curl -sf -H 'Content-Type: application/json' -d "$(jq -cn --arg q "$1" '{query: $q}')" "$APP/graphql"
}

graphql_returns_exactly_the_selected_fields() {
    local body
    body=$(graphql_query '{ orderSummary(orderId: "O-1001") { orderId amount } }') || return 1
    [[ "$(echo "$body" | jq -r '.data.orderSummary.orderId')" == "O-1001" ]] || return 1
    local keys
    keys=$(echo "$body" | jq -c '.data.orderSummary | keys | sort')
    [[ "$keys" == '["amount","orderId"]' ]]
}

schema_fetched() {
    SCHEMA_FILE=$(new_tmp_file)
    local status
    status=$(curl -s -o "$SCHEMA_FILE" -w '%{http_code}' "$APP/graphql/schema.graphql")
    [[ "$status" == "200" ]]
}
SCHEMA_FILE=""

served_schema_matches_committed_contract() {
    diff -q "$SCHEMA_FILE" "$CONTRACT_FILE" >/dev/null
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-token-efficient-response-bff; do
    check "$c is healthy" healthy "$c"
done

check "the full payload carries the heavy and internal fields" full_payload_carries_heavy_fields
check "the BFF projection omits internalNotes, auditTrail, customer and recordUuid" \
    bff_projection_omits_heavy_fields
check "the BFF projection carries the human-readable order id and no raw UUID anywhere" \
    bff_projection_carries_human_id_no_raw_uuid
check "a GraphQL query selecting exactly orderId and amount returns exactly those two fields" \
    graphql_returns_exactly_the_selected_fields
check "the GraphQL schema is served at /graphql/schema.graphql" schema_fetched
check "the served GraphQL schema matches the committed contract" served_schema_matches_committed_contract

# Measure the byte contrast: the full human payload versus both lean surfaces.
echo "--- measured byte contrast (full payload vs. BFF projections) ---"
FULL_BYTES=$(curl -sf -o /dev/null -w '%{size_download}' "$APP/orders/O-1001")
REST_BFF_BYTES=$(curl -sf -o /dev/null -w '%{size_download}' "$APP/orders/O-1001/summary")
GRAPHQL_BYTES=$(curl -sf -o /dev/null -w '%{size_download}' \
    -H 'Content-Type: application/json' \
    -d '{"query":"{ orderSummary(orderId: \"O-1001\") { orderId amount } }"}' "$APP/graphql")
echo "full REST payload:    bytes=$FULL_BYTES"
echo "REST BFF projection:  bytes=$REST_BFF_BYTES"
echo "GraphQL (2 fields):   bytes=$GRAPHQL_BYTES"
check "the REST BFF projection is materially fewer bytes than the full payload" \
    test "$REST_BFF_BYTES" -lt "$FULL_BYTES"
check "the GraphQL response is fewer bytes than the full REST payload" \
    test "$GRAPHQL_BYTES" -lt "$FULL_BYTES"

# The negative witness: wrapping the full entity as the BFF projection (instead of reshaping to
# OrderSummary), or making the GraphQL resolver over-return, must make the checks above fail.
# See README and _plans/verification-log.md for the bite that confirms it.

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
