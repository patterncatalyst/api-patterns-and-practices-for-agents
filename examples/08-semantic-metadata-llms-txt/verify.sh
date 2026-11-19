#!/usr/bin/env bash
# Starts the stack with the semantic-metadata-llms-txt app and checks that /llms.txt and the
# human /portal are both rendered from the single ApiMetadata bean: the set of operations named
# in one equals the set named in the other, and the served /llms.txt matches the committed
# contracts/08-llms.txt byte for byte.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
LLMS_TXT_FILE=../../contracts/08-llms.txt
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

ORDER_ID=""
place_order_succeeds() {
    local file status
    file=$(new_tmp_file)
    status=$(curl -s -H 'Content-Type: application/json' \
        -d '{"customerId":"CUST-1","itemSku":"SKU-1","quantity":2,"amount":49.99}' \
        -o "$file" -w '%{http_code}' "$APP/orders")
    ORDER_ID=$(jq -r '.orderId' "$file" 2>/dev/null || true)
    [[ "$status" == "201" ]]
}

get_order_succeeds() {
    local status
    status=$(curl -s -o /dev/null -w '%{http_code}' "$APP/orders/$ORDER_ID")
    [[ "$status" == "200" ]]
}

LLMS_FILE=""
llms_fetched() {
    LLMS_FILE=$(new_tmp_file)
    local status
    status=$(curl -s -o "$LLMS_FILE" -w '%{http_code}' "$APP/llms.txt")
    [[ "$status" == "200" ]]
}
llms_content_type_is_text_plain() {
    [[ "$(curl -s -o /dev/null -w '%{content_type}' "$APP/llms.txt")" == text/plain* ]]
}
llms_is_well_formed() {
    head -c 2 "$LLMS_FILE" | grep -q '^# ' \
        && grep -q '^> ' "$LLMS_FILE" \
        && grep -q '^## ' "$LLMS_FILE" \
        && grep -qE '^- \[[A-Za-z0-9]+\]\(' "$LLMS_FILE"
}

PORTAL_FILE=""
portal_fetched() {
    PORTAL_FILE=$(new_tmp_file)
    local status
    status=$(curl -s -o "$PORTAL_FILE" -w '%{http_code}' "$APP/portal")
    [[ "$status" == "200" ]]
}
portal_content_type_is_text_html() {
    [[ "$(curl -s -o /dev/null -w '%{content_type}' "$APP/portal")" == text/html* ]]
}

llms_operations() {
    grep -oE '#/operations/[A-Za-z0-9]+\)' "$LLMS_FILE" | sed -E 's#.*/##; s/\)$//' | sort -u
}
portal_operations() {
    grep -oE 'data-op="[A-Za-z0-9]+"' "$PORTAL_FILE" | sed -E 's/data-op="//; s/"$//' | sort -u
}
llms_and_portal_name_the_same_operations() {
    diff <(llms_operations) <(portal_operations) >/dev/null
}
operation_sets_are_not_empty() {
    [[ -n "$(llms_operations)" && -n "$(portal_operations)" ]]
}

served_llms_txt_matches_committed() {
    diff -q "$LLMS_FILE" "$LLMS_TXT_FILE" >/dev/null
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-semantic-metadata-llms-txt; do
    check "$c is healthy" healthy "$c"
done

check "placing an order succeeds with 201" place_order_succeeds
check "getting the order just placed succeeds with 200" get_order_succeeds

check "/llms.txt is served with 200" llms_fetched
check "/llms.txt is text/plain" llms_content_type_is_text_plain
check "/llms.txt is well-formed (H1, blockquote summary, ## sections, markdown links)" llms_is_well_formed
check "/portal is served with 200" portal_fetched
check "/portal is text/html" portal_content_type_is_text_html
check "the operation sets extracted from llms.txt and the portal are both non-empty" \
    operation_sets_are_not_empty
check "llms.txt and the portal name exactly the same set of operations" \
    llms_and_portal_name_the_same_operations
check "the served llms.txt matches the committed contracts/08-llms.txt" served_llms_txt_matches_committed

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
