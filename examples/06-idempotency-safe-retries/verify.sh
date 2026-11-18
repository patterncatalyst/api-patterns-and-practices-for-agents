#!/usr/bin/env bash
# Starts the stack with the idempotency-safe-retries app and checks that two POSTs carrying the
# same Idempotency-Key yield exactly one Order row, one OrderPlaced event and an identical
# response, while a repeat key with a different body is rejected instead of silently replayed.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
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

place_order() { # place_order <key> <body> <outfile>: prints "<http-status> <content-type>"
    curl -s -H 'Content-Type: application/json' -H "Idempotency-Key: $1" -d "$2" -o "$3" \
        -w '%{http_code} %{content_type}' "$APP/orders"
}

order_count() {
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT COUNT(*) FROM orders.orders"
}
order_count_is() { [[ "$(order_count)" == "$1" ]]; }

topic_count_for_key() { # topic_count_for_key <key>: number of records on apa.orders.placed keyed by <key>
    docker exec apa-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
        --topic apa.orders.placed --from-beginning --timeout-ms 15000 --property print.key=true 2>/dev/null \
        | grep -c "^$1"
}
topic_count_for_key_is() { [[ "$(topic_count_for_key "$1")" == "$2" ]]; }

BODY_SAME='{"customerId":"CUST-1","itemSku":"SKU-1","quantity":2,"amount":49.99}'
BODY_CONFLICT_FIRST='{"customerId":"CUST-2","itemSku":"SKU-2","quantity":1,"amount":10.00}'
BODY_CONFLICT_DIFFERENT='{"customerId":"CUST-2","itemSku":"SKU-9","quantity":1,"amount":10.00}'

FIRST_FILE=""
REPLAY_FILE=""
CONFLICT_FIRST_FILE=""
CONFLICT_SECOND_FILE=""

first_call_succeeds() {
    FIRST_FILE=$(new_tmp_file)
    local result
    result=$(place_order "idem-key-same" "$BODY_SAME" "$FIRST_FILE")
    [[ "${result%% *}" == "201" ]]
}

replay_call_is_identical() {
    REPLAY_FILE=$(new_tmp_file)
    local result
    result=$(place_order "idem-key-same" "$BODY_SAME" "$REPLAY_FILE")
    [[ "${result%% *}" == "201" ]] || return 1
    diff -q "$FIRST_FILE" "$REPLAY_FILE" >/dev/null
}

conflict_setup_succeeds() {
    CONFLICT_FIRST_FILE=$(new_tmp_file)
    local result
    result=$(place_order "idem-key-conflict" "$BODY_CONFLICT_FIRST" "$CONFLICT_FIRST_FILE")
    [[ "${result%% *}" == "201" ]]
}

conflict_call_is_rejected() {
    CONFLICT_SECOND_FILE=$(new_tmp_file)
    local result status ct
    result=$(place_order "idem-key-conflict" "$BODY_CONFLICT_DIFFERENT" "$CONFLICT_SECOND_FILE")
    status="${result%% *}"
    ct="${result#* }"
    [[ "$status" == "409" && "$ct" == application/problem+json* ]]
}

missing_key_is_rejected() {
    local status
    status=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
        -d "$BODY_SAME" "$APP/orders")
    [[ "$status" == "400" ]]
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-idempotency-safe-retries; do
    check "$c is healthy" healthy "$c"
done

check "a POST with a missing Idempotency-Key is rejected with 400" missing_key_is_rejected
check "the first POST with a new Idempotency-Key succeeds with 201" first_call_succeeds

ORDER_ID=$(jq -r '.orderId' "$FIRST_FILE" 2>/dev/null || true)

check "exactly one Order row exists after the first call" order_count_is 1
check "a second POST with the same key and the same body is replayed, byte-identical" \
    replay_call_is_identical
check "the Order row count is unchanged by the replay" order_count_is 1
check "exactly one OrderPlaced record keyed by the order id reached apa.orders.placed" \
    topic_count_for_key_is "$ORDER_ID" 1

check "a different key's first POST succeeds" conflict_setup_succeeds
CONFLICT_ORDER_ID=$(jq -r '.orderId' "$CONFLICT_FIRST_FILE" 2>/dev/null || true)
check "two distinct keys now account for exactly two Order rows" order_count_is 2
check "negative witness: the same key reused with a different body is rejected, not replayed" \
    conflict_call_is_rejected
check "the Order row count is unchanged by the rejected conflict" order_count_is 2
check "the conflicting attempt published nothing new for its order id" \
    topic_count_for_key_is "$CONFLICT_ORDER_ID" 1

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
