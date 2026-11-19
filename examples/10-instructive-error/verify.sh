#!/usr/bin/env bash
# Starts the stack with the instructive-error app, checks that each domain exception maps to a
# distinct application/problem+json body with all four RFC 9457 members plus an actionable
# remedy, and that the 409 names the order's current state and its allowed transitions, not a
# bare "conflict".
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
PROBLEM_JSON="application/problem+json"
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

# problem_fetched <method> <path> [body] -> sets PROBLEM_FILE, PROBLEM_STATUS, PROBLEM_CTYPE
PROBLEM_FILE=""
PROBLEM_STATUS=""
PROBLEM_CTYPE=""
problem_fetched() {
    local method="$1" path="$2" body="${3:-}"
    PROBLEM_FILE=$(new_tmp_file)
    if [[ -n "$body" ]]; then
        PROBLEM_STATUS=$(curl -s -X "$method" -H 'Content-Type: application/json' -d "$body" \
            -o "$PROBLEM_FILE" -w '%{http_code}' "$APP$path")
        PROBLEM_CTYPE=$(curl -s -X "$method" -H 'Content-Type: application/json' -d "$body" \
            -o /dev/null -w '%{content_type}' "$APP$path")
    else
        PROBLEM_STATUS=$(curl -s -X "$method" -o "$PROBLEM_FILE" -w '%{http_code}' "$APP$path")
        PROBLEM_CTYPE=$(curl -s -X "$method" -o /dev/null -w '%{content_type}' "$APP$path")
    fi
}

pjq() { jq -r "$1" "$PROBLEM_FILE"; } # pjq <jq filter>: reads the already-fetched problem body

is_rfc9457_problem() { # is_rfc9457_problem <expected-status>
    [[ "$PROBLEM_STATUS" == "$1" ]] || return 1
    [[ "$PROBLEM_CTYPE" == "$PROBLEM_JSON"* ]] || return 1
    [[ -n "$(pjq '.type')" && "$(pjq '.type')" != "null" ]] || return 1
    [[ -n "$(pjq '.title')" && "$(pjq '.title')" != "null" ]] || return 1
    [[ "$(pjq '.status')" == "$1" ]] || return 1
    [[ -n "$(pjq '.detail')" && "$(pjq '.detail')" != "null" ]] || return 1
}

ORDER_ID=""
place_order_succeeds() {
    problem_fetched POST /orders '{"customerId":"CUST-1","itemSku":"SKU-1","quantity":2,"amount":49.99}'
    [[ "$PROBLEM_STATUS" == "201" ]] || return 1
    ORDER_ID=$(pjq '.orderId')
    [[ "$(pjq '.status')" == "PLACED" ]]
}

unknown_order_is_instructive_not_found() {
    problem_fetched GET /orders/O-NOPE
    is_rfc9457_problem 404 || return 1
    [[ -n "$(pjq '.nextStep')" && "$(pjq '.nextStep')" != "null" ]]
}

invalid_quantity_is_instructive_validation_naming_quantity() {
    problem_fetched POST /orders '{"customerId":"CUST-2","itemSku":"SKU-1","quantity":0,"amount":5.00}'
    is_rfc9457_problem 400 || return 1
    [[ "$(pjq '.field')" == "quantity" ]]
}

blank_customer_id_is_instructive_validation_naming_customerId() {
    problem_fetched POST /orders '{"customerId":"","itemSku":"SKU-1","quantity":1,"amount":5.00}'
    is_rfc9457_problem 400 || return 1
    [[ "$(pjq '.field')" == "customerId" ]]
}

cancel_placed_order_succeeds() {
    problem_fetched POST "/orders/$ORDER_ID/cancel"
    [[ "$PROBLEM_STATUS" == "200" ]] && [[ "$(pjq '.status')" == "CANCELLED" ]]
}

second_cancel_is_instructive_conflict_naming_cancelled_and_no_transitions() {
    problem_fetched POST "/orders/$ORDER_ID/cancel"
    is_rfc9457_problem 409 || return 1
    [[ "$(pjq '.currentState')" == "CANCELLED" ]] || return 1
    [[ "$(pjq '.allowedTransitions | length')" == "0" ]]
}

cancel_seeded_shipped_order_names_delivered_as_the_only_transition() {
    problem_fetched POST "/orders/O-2002/cancel"
    is_rfc9457_problem 409 || return 1
    [[ "$(pjq '.currentState')" == "SHIPPED" ]] || return 1
    [[ "$(pjq '.allowedTransitions | sort | join(",")')" == "DELIVERED" ]]
}

the_three_problem_types_are_distinct() {
    local not_found_type validation_type conflict_type
    problem_fetched GET /orders/O-NOPE
    not_found_type=$(pjq '.type')
    problem_fetched POST /orders '{"customerId":"CUST-3","itemSku":"SKU-1","quantity":0,"amount":5.00}'
    validation_type=$(pjq '.type')
    problem_fetched POST "/orders/O-2002/cancel"
    conflict_type=$(pjq '.type')
    [[ "$not_found_type" != "$validation_type" && "$validation_type" != "$conflict_type" \
        && "$not_found_type" != "$conflict_type" ]]
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-instructive-error; do
    check "$c is healthy" healthy "$c"
done

check "placing an order succeeds with 201" place_order_succeeds
check "an unknown order id is an instructive 404 problem+json with a nextStep remedy" \
    unknown_order_is_instructive_not_found
check "an invalid quantity is an instructive 400 problem+json naming the quantity field" \
    invalid_quantity_is_instructive_validation_naming_quantity
check "a blank customerId is an instructive 400 problem+json naming the customerId field" \
    blank_customer_id_is_instructive_validation_naming_customerId
check "cancelling the placed order succeeds" cancel_placed_order_succeeds
check "cancelling it again is an instructive 409 naming CANCELLED and no allowed transitions" \
    second_cancel_is_instructive_conflict_naming_cancelled_and_no_transitions
check "cancelling the seeded SHIPPED order names DELIVERED as the only allowed transition" \
    cancel_seeded_shipped_order_names_delivered_as_the_only_transition
check "the three problem types (404, 400, 409) are distinct" the_three_problem_types_are_distinct

# The negative witness: removing the @ServerExceptionMapper methods must make the
# content-type and remedy-member checks above fail. See README and
# _plans/verification-log.md for the bite that confirms it.

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
