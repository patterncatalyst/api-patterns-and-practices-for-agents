#!/usr/bin/env bash
# Starts the stack with two app images: the separate payment-service (gRPC only, no host port)
# and the mcp-facade in front of it. Drives the MCP initialize handshake, tools/list, and two
# tools/call reshapes against the facade over Streamable HTTP: takePayment (a decimal amount
# reshaped to cents on the way in, and the gRPC response reshaped back to decimal JSON on the way
# out, against a real gRPC call to the separate payment-service image) and refundPayment against
# the seeded order O-9001.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
TOOLS_CONTRACT=../../contracts/15-payment-tools.mcp.json
TOTAL=0
PASSED=0
TMP_FILES=()
MCP_SESSION_ID=""
PROTOCOL_REVISION=""

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

mcp_rpc() { # mcp_rpc <json-body>: POSTs to /mcp with the session header (if set), prints the body
    local headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
    if [[ -n "$MCP_SESSION_ID" ]]; then
        headers+=(-H "Mcp-Session-Id: $MCP_SESSION_ID")
    fi
    curl -sf "${headers[@]}" -d "$1" "$APP/mcp"
}

mcp_initialize() { # sets MCP_SESSION_ID and PROTOCOL_REVISION as a side effect
    local header_file body_file status
    header_file=$(new_tmp_file)
    body_file=$(new_tmp_file)
    status=$(curl -s -D "$header_file" -o "$body_file" -w '%{http_code}' \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"verify-sh","version":"1.0.0"}}}' \
        "$APP/mcp")
    [[ "$status" == "200" ]] || return 1
    MCP_SESSION_ID=$(grep -i '^mcp-session-id:' "$header_file" | tr -d '\r' | cut -d' ' -f2)
    PROTOCOL_REVISION=$(jq -r '.result.protocolVersion' "$body_file")
    [[ -n "$MCP_SESSION_ID" ]] || return 1
    mcp_rpc '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
}

initialize_negotiates_the_expected_protocol_revision() {
    mcp_initialize
    [[ "$PROTOCOL_REVISION" == "2025-11-25" ]]
}

TOOLS_LIST_FILE=""
tools_list_advertises_the_reshaped_payment_tools() {
    TOOLS_LIST_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' > "$TOOLS_LIST_FILE"
    for name in takePayment refundPayment; do
        jq -e --arg n "$name" '.result.tools[] | select(.name == $n) | has("inputSchema")' "$TOOLS_LIST_FILE" >/dev/null
    done
}

committed_tools_contract_matches_the_served_tools_list() {
    diff -q <(jq '.result.tools' "$TOOLS_LIST_FILE") "$TOOLS_CONTRACT" >/dev/null
}

TAKE_PAYMENT_FILE=""
take_payment_tool_call_succeeds_for_a_fresh_order() {
    TAKE_PAYMENT_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"takePayment","arguments":{"orderId":"O-7777","amount":"49.99"}}}' \
        > "$TAKE_PAYMENT_FILE"
    jq -e '.result.content[0].text | fromjson | .orderId == "O-7777"' "$TAKE_PAYMENT_FILE" >/dev/null
}

take_payment_reshapes_the_decimal_amount_through_a_real_grpc_call() {
    # The round-trip this example exists to prove: "49.99" went in as the wire-level int64 cents
    # 4999 to the separate payment-service image over gRPC, and came back out as the decimal
    # string "49.99" with the gRPC-side COMPLETED status reshaped to readable JSON.
    jq -e '.result.content[0].text | fromjson | .amount == "49.99" and .status == "COMPLETED"' \
        "$TAKE_PAYMENT_FILE" >/dev/null
}

REFUND_PAYMENT_FILE=""
refund_payment_tool_call_succeeds_for_the_seeded_order() {
    REFUND_PAYMENT_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"refundPayment","arguments":{"orderId":"O-9001","amount":"49.99"}}}' \
        > "$REFUND_PAYMENT_FILE"
    jq -e '.result.content[0].text | fromjson | .orderId == "O-9001" and .status == "REFUNDED"' \
        "$REFUND_PAYMENT_FILE" >/dev/null
}

refund_payment_for_an_unknown_order_returns_an_instructive_tool_error() {
    local f; f=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"refundPayment","arguments":{"orderId":"O-0000","amount":"10.00"}}}' \
        > "$f"
    jq -e '.result.isError == true and (.result.content[0].text | contains("No payment found"))' "$f" >/dev/null
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted run),
# then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm \
        apa-15-payment-service apa-15-mcp-facade; do
    check "$c is healthy" healthy "$c"
done

check "MCP initialize negotiates protocol revision 2025-11-25" initialize_negotiates_the_expected_protocol_revision
echo "--- advertised MCP protocol revision: ${PROTOCOL_REVISION:-unknown} ---"

check "tools/list advertises takePayment and refundPayment with inputSchemas" \
    tools_list_advertises_the_reshaped_payment_tools
check "the committed contracts/15-payment-tools.mcp.json matches the served tools/list" \
    committed_tools_contract_matches_the_served_tools_list

check "the takePayment tools/call succeeds for a fresh order" take_payment_tool_call_succeeds_for_a_fresh_order
check "takePayment reshapes the decimal amount to cents, calls the separate payment-service over gRPC, and reshapes the response back to decimal JSON" \
    take_payment_reshapes_the_decimal_amount_through_a_real_grpc_call

check "the refundPayment tools/call succeeds for the seeded order O-9001" \
    refund_payment_tool_call_succeeds_for_the_seeded_order
check "refundPayment for an unknown order returns an instructive tool error, not a raw gRPC status" \
    refund_payment_for_an_unknown_order_returns_an_instructive_tool_error

# The negative witness (not run by this script; the orchestrator runs it and records the bite in
# _plans/verification-log.md): bypass AmountReshape and pass the JSON decimal amount straight
# through as amount_cents (for example TakePaymentRequest.newBuilder().setAmountCents(49) instead
# of 4999 for "49.99", or build-flag the facade to return the raw Payment.getAmountCents() value
# unformatted instead of AmountReshape.toDecimal(...)). Either change must make the
# "take_payment_reshapes_the_decimal_amount_through_a_real_grpc_call" check above fail: a payment
# of $0.49 at the payment-service instead of $49.99, or an amount field that round-trips as an
# integer cents value instead of the decimal string "49.99". Restoring AmountReshape returns the
# response to byte-identical with the committed example and the check passes again.

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
