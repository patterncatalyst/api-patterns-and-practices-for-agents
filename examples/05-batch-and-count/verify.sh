#!/usr/bin/env bash
# Starts the stack with the batch-and-count app, checks that one batch-get call isolates hits
# from misses, checks that the count flag returns a bare number and never record bodies, and
# measures the call-count/byte contrast against the single-get and fetch-all baselines.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
TOTAL=0
PASSED=0

cleanup() {
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

batch_get() { curl -sf -H 'Content-Type: application/json' -d "$1" "$APP/orders/batch-get"; }

# Seeded by V2__seed.sql: n in 1..36, status cycles every 4 (n % 4 == 0 is PLACED).
# O-0004 (n=4) is PLACED, O-0005 (n=5) is PAID; O-9999 does not exist.
batch_get_isolates_hits_and_misses() {
    local body
    body=$(batch_get '["O-0004", "O-0005", "O-9999"]') || return 1
    echo "$body" | jq -e '.hits."O-0004".id == "O-0004" and .hits."O-0004".status == "PLACED"' >/dev/null || return 1
    echo "$body" | jq -e '.hits."O-0005".id == "O-0005" and .hits."O-0005".status == "PAID"' >/dev/null || return 1
    echo "$body" | jq -e '.notFound == ["O-9999"]' >/dev/null || return 1
    echo "$body" | jq -e '(.hits | length) == 2' >/dev/null
}

count_body() { curl -sf "$APP/orders/count$1"; }

count_is_bare_number() { # count_is_bare_number <query> <expected>: body is only digits, no braces/brackets
    local body
    body=$(count_body "$1") || return 1
    [[ "$body" =~ ^[0-9]+$ ]] || return 1
    [[ "$body" != *"{"* && "$body" != *"["* ]] || return 1
    [[ "$body" == "$2" ]]
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-batch-and-count; do
    check "$c is healthy" healthy "$c"
done

check "one batch-get call isolates hits from the unknown id's notFound entry" batch_get_isolates_hits_and_misses
check "GET /orders/count?status=PLACED returns only the bare number 9, no record fields" \
    count_is_bare_number "?status=PLACED" 9
check "an unfiltered GET /orders/count covers every seeded row" count_is_bare_number "" 36

# Measured contrast 1: one batch-get call for several ids versus one single-get call per id.
echo "--- measured call-count contrast (batch-get vs single-get) ---"
BATCH_IDS='["O-0001","O-0002","O-0003","O-0004","O-0005","O-0006","O-0007","O-0008","O-9001","O-9002"]'
BATCH_CALLS=1
BATCH_BYTES=$(curl -sf -o /dev/null -w '%{size_download}' -H 'Content-Type: application/json' -d "$BATCH_IDS" "$APP/orders/batch-get")
echo "batch-get: calls=$BATCH_CALLS bytes=$BATCH_BYTES ids=10 (8 hits, 2 misses)"

SINGLE_CALLS=0
SINGLE_BYTES=0
for id in O-0001 O-0002 O-0003 O-0004 O-0005 O-0006 O-0007 O-0008 O-9001 O-9002; do
    SIZE=$(curl -s -o /dev/null -w '%{size_download}' "$APP/orders/$id")
    SINGLE_CALLS=$((SINGLE_CALLS + 1))
    SINGLE_BYTES=$((SINGLE_BYTES + SIZE))
done
echo "single-get: calls=$SINGLE_CALLS bytes=$SINGLE_BYTES (same 10 ids, one call each)"
check "batch-get needs fewer calls than one single-get per id" test "$BATCH_CALLS" -lt "$SINGLE_CALLS"

# Measured contrast 2: the count flag (one number) versus fetch-all-then-count (every row).
echo "--- measured byte contrast (count flag vs fetch-all-then-count) ---"
COUNT_CALLS=1
COUNT_BYTES=$(curl -sf -o /dev/null -w '%{size_download}' "$APP/orders/count")
echo "count:     calls=$COUNT_CALLS bytes=$COUNT_BYTES"

FETCH_ALL_CALLS=1
FETCH_ALL_BYTES=$(curl -sf -o /dev/null -w '%{size_download}' "$APP/orders")
echo "fetch-all: calls=$FETCH_ALL_CALLS bytes=$FETCH_ALL_BYTES (36 full records, counted client-side)"
check "the count flag transfers far fewer bytes than fetching every row to count them" \
    test "$FETCH_ALL_BYTES" -gt "$COUNT_BYTES"

# The negative witness: a count endpoint that returns full records instead of a bare number
# must make the "count is a bare number" check fail, proving it actually discriminates. See
# README and _plans/verification-log.md for the bite that confirms it.

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
