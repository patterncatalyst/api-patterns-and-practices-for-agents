#!/usr/bin/env bash
# Starts the stack with the search-over-pagination app, checks that a single filtered search
# call isolates exactly its target, and measures the call-count contrast against raw paging.
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

search() { curl -sf "$APP/orders/search?status=PLACED&itemSku=SKU-2"; }

# Seeded by V2__seed.sql: n in 1..60, n % 5 == 0 is PLACED, and of those n % 4 == 1 is SKU-2.
EXPECTED_IDS=(O-0005 O-0025 O-0045)

search_isolates_target() {
    local body count
    body=$(search) || return 1
    count=$(echo "$body" | jq 'length')
    [[ "$count" == "${#EXPECTED_IDS[@]}" ]] || return 1
    for id in "${EXPECTED_IDS[@]}"; do
        echo "$body" | jq -e --arg id "$id" 'any(.[]; .id == $id)' >/dev/null || return 1
    done
    echo "$body" | jq -e 'all(.[]; .status == "PLACED" and .itemSku == "SKU-2")' >/dev/null
}

search_result_capped() {
    local count
    count=$(curl -sf "$APP/orders/search" | jq 'length')
    [[ "$count" -le 50 ]]
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-search-over-pagination; do
    check "$c is healthy" healthy "$c"
done

check "one search call returns exactly the 3 matching orders and excludes every other" search_isolates_target
check "an unfiltered search is still bounded by the result cap" search_result_capped

# Measure the round-trip contrast: one search call versus a naive pagination sweep that does
# not know in advance which page holds the target, so it must page through every page that
# has data (6 calls for 60 rows at size 10) to be sure it has seen everything.
echo "--- measured call-count contrast (search vs. raw pagination) ---"
SEARCH_CALLS=1
SEARCH_BYTES=$(curl -sf -o /dev/null -w '%{size_download}' "$APP/orders/search?status=PLACED&itemSku=SKU-2")
echo "search:  calls=$SEARCH_CALLS bytes=$SEARCH_BYTES matches=${#EXPECTED_IDS[@]}"

PAGE_CALLS=0
PAGE_BYTES=0
PAGE=0
while :; do
    RESP_FILE=$(mktemp)
    SIZE=$(curl -sf -o "$RESP_FILE" -w '%{size_download}' "$APP/orders?page=$PAGE&size=10")
    PAGE_CALLS=$((PAGE_CALLS + 1))
    PAGE_BYTES=$((PAGE_BYTES + SIZE))
    HAS_MORE=$(jq -r '.hasMore' "$RESP_FILE")
    rm -f "$RESP_FILE"
    [[ "$HAS_MORE" == "true" ]] || break
    PAGE=$((PAGE + 1))
done
echo "paging:  calls=$PAGE_CALLS bytes=$PAGE_BYTES (60 rows, page size 10, matches scattered across every page)"
check "the paging sweep needs more calls than the one search call" test "$PAGE_CALLS" -gt "$SEARCH_CALLS"
check "the paging sweep transfers more bytes than the one search call" test "$PAGE_BYTES" -gt "$SEARCH_BYTES"

# The negative witness: ignoring the filter (returning the unfiltered first page instead) must
# make this check fail, proving the assertion actually discriminates. See README and
# _plans/verification-log.md for the bite that confirms it.

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
