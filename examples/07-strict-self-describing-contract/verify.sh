#!/usr/bin/env bash
# Starts the stack with the strict-self-describing-contract app and checks that the generated
# OpenAPI document at /q/openapi is 3.1.x, declares a stable operationId per operation, the
# OrderStatus enum, the OrderCreateRequest required fields, and application/problem+json error
# responses, and that the served document matches the committed
# contracts/07-order-service.openapi.yaml once both are normalized to sorted-key JSON.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
CONTRACT_FILE=../../contracts/07-order-service.openapi.yaml
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

# Prints canonical, sorted-key JSON for a JSON or YAML OpenAPI document, so a served document and
# a committed document can be diffed regardless of key order or JSON-vs-YAML formatting.
normalize() {
    python3 - "$1" <<'PY'
import json
import sys

import yaml

with open(sys.argv[1], "r", encoding="utf-8") as f:
    text = f.read()
try:
    data = json.loads(text)
except json.JSONDecodeError:
    data = yaml.safe_load(text)
print(json.dumps(data, sort_keys=True, indent=2))
PY
}

OPENAPI_FILE=""
openapi_fetched() {
    OPENAPI_FILE=$(new_tmp_file)
    local status
    status=$(curl -s -o "$OPENAPI_FILE" -w '%{http_code}' -H 'Accept: application/json' "$APP/q/openapi")
    [[ "$status" == "200" ]]
}

jqv() { jq -r "$1" "$OPENAPI_FILE"; } # jqv <jq filter>: reads the already-fetched OpenAPI document

openapi_version_is_3_1() { [[ "$(jqv '.openapi')" == 3.1.* ]]; }

place_order_operation_id_is_stable() { [[ "$(jqv '.paths."/orders".post.operationId')" == "placeOrder" ]]; }
get_order_operation_id_is_stable() { [[ "$(jqv '.paths."/orders/{id}".get.operationId')" == "getOrder" ]]; }

order_status_enum_has_5_values() {
    [[ "$(jqv '.components.schemas.OrderResponse.properties.status.enum | length')" == "5" ]]
}
order_status_enum_matches() {
    local values
    values=$(jqv '.components.schemas.OrderResponse.properties.status.enum | sort | join(",")')
    [[ "$values" == "CANCELLED,DELIVERED,PAID,PLACED,SHIPPED" ]]
}

create_request_required_matches() {
    local required
    required=$(jqv '.components.schemas.OrderCreateRequest.required | sort | join(",")')
    [[ "$required" == "amount,customerId,itemSku,quantity" ]]
}

place_order_has_problem_json_400() {
    [[ "$(jqv '.paths."/orders".post.responses."400".content."application/problem+json" != null')" == "true" ]]
}
get_order_has_problem_json_404() {
    [[ "$(jqv '.paths."/orders/{id}".get.responses."404".content."application/problem+json" != null')" == "true" ]]
}

served_matches_committed_contract() {
    local served_norm committed_norm
    served_norm=$(new_tmp_file)
    committed_norm=$(new_tmp_file)
    curl -s -o "$(new_tmp_file)" "$APP/q/openapi" # exercise the default (YAML) response path too
    normalize "$OPENAPI_FILE" > "$served_norm"
    normalize "$CONTRACT_FILE" > "$committed_norm"
    diff -q "$served_norm" "$committed_norm" >/dev/null
}

place_order_succeeds() {
    local file
    file=$(new_tmp_file)
    local status
    status=$(curl -s -H 'Content-Type: application/json' \
        -d '{"customerId":"CUST-1","itemSku":"SKU-1","quantity":2,"amount":49.99}' \
        -o "$file" -w '%{http_code}' "$APP/orders")
    ORDER_ID=$(jq -r '.orderId' "$file" 2>/dev/null || true)
    [[ "$status" == "201" ]]
}
ORDER_ID=""

get_order_succeeds() {
    local status
    status=$(curl -s -o /dev/null -w '%{http_code}' "$APP/orders/$ORDER_ID")
    [[ "$status" == "200" ]]
}

get_unknown_order_is_problem_json_404() {
    local file status ct
    file=$(new_tmp_file)
    status=$(curl -s -o "$file" -w '%{http_code}' "$APP/orders/O-NOPE")
    ct=$(curl -s -o /dev/null -w '%{content_type}' "$APP/orders/O-NOPE")
    [[ "$status" == "404" && "$ct" == application/problem+json* ]]
}

place_invalid_order_is_problem_json_400() {
    local ct status
    status=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
        -d '{"customerId":"CUST-2","itemSku":"SKU-1","quantity":0,"amount":5.00}' "$APP/orders")
    ct=$(curl -s -o /dev/null -w '%{content_type}' -H 'Content-Type: application/json' \
        -d '{"customerId":"CUST-2","itemSku":"SKU-1","quantity":0,"amount":5.00}' "$APP/orders")
    [[ "$status" == "400" && "$ct" == application/problem+json* ]]
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-strict-self-describing-contract; do
    check "$c is healthy" healthy "$c"
done

check "placing an order succeeds with 201" place_order_succeeds
check "getting the order just placed succeeds with 200" get_order_succeeds
check "getting an unknown order id is rejected with 404 problem+json" get_unknown_order_is_problem_json_404
check "placing an invalid order (quantity 0) is rejected with 400 problem+json" \
    place_invalid_order_is_problem_json_400

check "/q/openapi is served and fetched as JSON" openapi_fetched
check "the served document declares openapi 3.1.x" openapi_version_is_3_1
check "placeOrder has a stable operationId" place_order_operation_id_is_stable
check "getOrder has a stable operationId" get_order_operation_id_is_stable
check "OrderResponse.status declares exactly 5 enum values" order_status_enum_has_5_values
check "OrderResponse.status enumerates the 5 OrderStatus values" order_status_enum_matches
check "OrderCreateRequest declares its 4 fields required" create_request_required_matches
check "POST /orders declares an application/problem+json 400 response" place_order_has_problem_json_400
check "GET /orders/{id} declares an application/problem+json 404 response" get_order_has_problem_json_404
check "the served OpenAPI document matches the committed contract, normalized" \
    served_matches_committed_contract

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
