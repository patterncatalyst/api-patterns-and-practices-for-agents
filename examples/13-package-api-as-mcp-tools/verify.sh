#!/usr/bin/env bash
# Starts the stack with the package-api-as-mcp-tools app and drives the full MCP triad over
# Streamable HTTP: the initialize handshake, tools/list (the reshaped orderStatus tool plus two
# narrow supporting reads), resources/list and resources/read (the classpath-loaded playbook),
# prompts/list and prompts/get (the templated order-status summary), and one orderStatus
# tools/call that must return a response composed from all three schemas (orders, payments,
# shipping) in a single call.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
TOOLS_CONTRACT=../../contracts/13-order-tools.mcp.json
RESOURCES_CONTRACT=../../contracts/13-order-resources.mcp.json
PROMPTS_CONTRACT=../../contracts/13-order-prompts.mcp.json
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
tools_list_advertises_the_reshaped_tools_with_schemas() {
    TOOLS_LIST_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' > "$TOOLS_LIST_FILE"
    for name in orderStatus paymentStatus shipmentTracking; do
        jq -e --arg n "$name" '.result.tools[] | select(.name == $n) | has("inputSchema")' "$TOOLS_LIST_FILE" >/dev/null
    done
}

committed_tools_contract_matches_the_served_tools_list() {
    diff -q <(jq '.result.tools' "$TOOLS_LIST_FILE") "$TOOLS_CONTRACT" >/dev/null
}

RESOURCES_LIST_FILE=""
resources_list_advertises_the_playbook() {
    RESOURCES_LIST_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":3,"method":"resources/list","params":{}}' > "$RESOURCES_LIST_FILE"
    jq -e '.result.resources[] | select(.uri == "skill://orders/status-playbook")' "$RESOURCES_LIST_FILE" >/dev/null
}

committed_resources_contract_matches_the_served_resources_list() {
    diff -q <(jq '.result.resources' "$RESOURCES_LIST_FILE") "$RESOURCES_CONTRACT" >/dev/null
}

RESOURCE_READ_FILE=""
resources_read_returns_the_playbook_text() {
    RESOURCE_READ_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"skill://orders/status-playbook"}}' \
        > "$RESOURCE_READ_FILE"
    jq -e '.result.contents[0].text | contains("Order status")' "$RESOURCE_READ_FILE" >/dev/null
}

PROMPTS_LIST_FILE=""
prompts_list_advertises_the_summary_prompt() {
    PROMPTS_LIST_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":5,"method":"prompts/list","params":{}}' > "$PROMPTS_LIST_FILE"
    jq -e '.result.prompts[] | select(.name == "summarizeOrderStatus")' "$PROMPTS_LIST_FILE" >/dev/null
}

committed_prompts_contract_matches_the_served_prompts_list() {
    diff -q <(jq '.result.prompts' "$PROMPTS_LIST_FILE") "$PROMPTS_CONTRACT" >/dev/null
}

PROMPT_GET_FILE=""
prompts_get_renders_the_summary_for_the_seeded_order() {
    PROMPT_GET_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":6,"method":"prompts/get","params":{"name":"summarizeOrderStatus","arguments":{"orderId":"O-9001"}}}' \
        > "$PROMPT_GET_FILE"
    jq -e '.result.messages[0].content.text | contains("O-9001")' "$PROMPT_GET_FILE" >/dev/null
}

ORDER_STATUS_FILE=""
order_status_tool_call_succeeds() {
    ORDER_STATUS_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"orderStatus","arguments":{"orderId":"O-9001"}}}' \
        > "$ORDER_STATUS_FILE"
    jq -e '.result.content[0].text | fromjson | .orderId == "O-9001"' "$ORDER_STATUS_FILE" >/dev/null
}

order_status_is_composed_from_all_three_schemas_in_one_call() {
    jq -e '.result.content[0].text | fromjson
        | (.orderStatus == "SHIPPED") and (.paymentStatus == "COMPLETED") and (.paymentAmount != null)
          and (.carrier == "FASTLINE") and (.trackingNumber == "FL-778821") and (.shipmentStatus == "IN_TRANSIT")' \
        "$ORDER_STATUS_FILE" >/dev/null
}

payment_status_tool_stays_narrow() {
    local f; f=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"paymentStatus","arguments":{"orderId":"O-9001"}}}' \
        > "$f"
    jq -e '.result.content[0].text | fromjson | .status == "COMPLETED" and (has("carrier") | not)' "$f" >/dev/null
}

shipment_tracking_tool_stays_narrow() {
    local f; f=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"shipmentTracking","arguments":{"orderId":"O-9001"}}}' \
        > "$f"
    jq -e '.result.content[0].text | fromjson | .trackingNumber == "FL-778821" and (has("paymentStatus") | not)' "$f" >/dev/null
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-package-api-as-mcp-tools; do
    check "$c is healthy" healthy "$c"
done

check "MCP initialize negotiates protocol revision 2025-11-25" initialize_negotiates_the_expected_protocol_revision
echo "--- advertised MCP protocol revision: ${PROTOCOL_REVISION:-unknown} ---"

check "tools/list advertises orderStatus, paymentStatus and shipmentTracking with inputSchemas" \
    tools_list_advertises_the_reshaped_tools_with_schemas
check "the committed contracts/13-order-tools.mcp.json matches the served tools/list" \
    committed_tools_contract_matches_the_served_tools_list

check "resources/list advertises skill://orders/status-playbook" \
    resources_list_advertises_the_playbook
check "the committed contracts/13-order-resources.mcp.json matches the served resources/list" \
    committed_resources_contract_matches_the_served_resources_list
check "resources/read returns the classpath-loaded playbook text" \
    resources_read_returns_the_playbook_text

check "prompts/list advertises summarizeOrderStatus" \
    prompts_list_advertises_the_summary_prompt
check "the committed contracts/13-order-prompts.mcp.json matches the served prompts/list" \
    committed_prompts_contract_matches_the_served_prompts_list
check "prompts/get renders the summary naming the seeded order" \
    prompts_get_renders_the_summary_for_the_seeded_order

check "the orderStatus tools/call succeeds for the seeded order" order_status_tool_call_succeeds
check "orderStatus composes order, payment and shipment into one response" \
    order_status_is_composed_from_all_three_schemas_in_one_call
check "paymentStatus stays a narrow read with no shipment fields" payment_status_tool_stays_narrow
check "shipmentTracking stays a narrow read with no payment fields" shipment_tracking_tool_stays_narrow

# The negative witness: reducing OrderStatusReads.orderStatus to a passthrough of the raw order
# row (no delegation to paymentStatus/shipmentTracking) must make the "composed from all three
# schemas" check above fail, since the payment and shipment fields come back null. See README and
# _plans/verification-log.md for the bite that confirms it.

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
