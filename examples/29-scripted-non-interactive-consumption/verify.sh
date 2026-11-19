#!/usr/bin/env bash
# Starts ch07's strict-self-describing-contract order service fresh and drives it the way a human
# would drive the same tool contract by hand, but scripted and non-interactive: Newman (the
# Postman collection runner) places an order, reads it back, asks for an order that does not exist
# and submits an invalid order, asserting status, content type and body shape on each, including
# the two problem+json error paths. Newman runs as a pinned one-shot container on the apa network
# and reaches the service by compose service DNS, never through the host port. The run asserts on
# Newman's JSON report (its pass/fail counts and the specific unknown-order assertion), never on
# Newman's exit code. KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
# Pinned one-shot runner (see _plans/versions.md): tag plus digest, Apache-2.0.
NEWMAN_IMAGE="postman/newman:6.1.3-alpine@sha256:d9b5e780ead0026bbb37f3759cd7b10fc8a88cd4030c8266a7c5e343d75a5198"
APP_SERVICE=strict-self-describing-contract
BASE_URL="http://${APP_SERVICE}:8080"
COLLECTION="$PWD/order-service.postman_collection.json"
UNKNOWN_ORDER_REQUEST="unknown order returns 404 problem+json"
TOTAL=0
PASSED=0
REPORT_DIR=""
REPORT=""

cleanup() {
    if [[ -n "$REPORT_DIR" ]]; then
        rm -rf "$REPORT_DIR" 2>/dev/null || true
    fi
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

report_exists_and_parses() { [[ -f "$REPORT" ]] && jq -e '.run.stats' "$REPORT" >/dev/null; }

all_four_requests_ran() { [[ "$(jq -r '.run.stats.requests.total // 0' "$REPORT")" -ge 4 ]]; }

# The collection run reports every assertion passed: a positive total and zero failures.
every_assertion_passed() {
    local failed total
    failed=$(jq -r '.run.stats.assertions.failed // 1' "$REPORT")
    total=$(jq -r '.run.stats.assertions.total // 0' "$REPORT")
    [[ "$total" -gt 0 && "$failed" == "0" ]]
}

# The biting negative witness, read straight from the JSON report: the unknown-order request must
# have run its assertions and every one must have passed (no .error). When ch07 drifts (its
# exception mappers removed, so GET /orders/O-NOPE falls to 500 text/plain), the status and
# content-type assertions on this request gain an .error in the report, so this check FAILS and
# the drift is observable without reading Newman's exit code.
unknown_order_404_assertion_held() {
    jq -e --arg name "$UNKNOWN_ORDER_REQUEST" '
        [ .run.executions[]
          | select(.item.name == $name)
          | .assertions[]? ] as $a
        | ($a | length) > 0 and (all($a[]; .error == null))
    ' "$REPORT" >/dev/null
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted run),
# then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-29-strict-self-describing-contract; do
    check "$c is healthy" healthy "$c"
done

# Drive the contract. Newman writes its JSON report to a host-mounted directory so the host can
# read the assertion results; the report is transient and removed on exit. Run as the host user so
# the report file is host-owned. Newman's exit code is deliberately ignored (|| true): the run is
# judged by the JSON report below, never by the exit code.
REPORT_DIR=$(mktemp -d)
REPORT="$REPORT_DIR/report.json"
docker run --rm --network apa \
    --user "$(id -u):$(id -g)" \
    -v "$COLLECTION:/etc/newman/collection.json:ro" \
    -v "$REPORT_DIR:/etc/newman/out" \
    "$NEWMAN_IMAGE" \
    run /etc/newman/collection.json \
    --env-var "baseUrl=$BASE_URL" \
    --reporters cli,json \
    --reporter-json-export /etc/newman/out/report.json || true

check "the Newman JSON report was written and parses" report_exists_and_parses
check "the collection ran all four requests" all_four_requests_ran
check "every collection assertion passed (0 failed)" every_assertion_passed
check "the unknown-order 404 problem+json assertion held (biting negative witness)" \
    unknown_order_404_assertion_held

if [[ -f "$REPORT" ]]; then
    NM_TOTAL=$(jq -r '.run.stats.assertions.total // 0' "$REPORT")
    NM_FAILED=$(jq -r '.run.stats.assertions.failed // 0' "$REPORT")
    echo "--- Newman assertions: $((NM_TOTAL - NM_FAILED))/$NM_TOTAL passed ---"
fi

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
