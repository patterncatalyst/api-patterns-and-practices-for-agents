#!/usr/bin/env bash
# Starts ch13's package-api-as-mcp-tools server fresh, published to the host exactly as the
# committed .mcp.json expects a local Claude Code to reach it, and drives the same client
# lifecycle mcp-client.sh and an interactive Claude Code session both perform: the initialize
# handshake, tools/list, resources/list, prompts/list, a tools/call for the seeded order O-9001,
# and a negative witness proving a tool the server never advertised cannot be driven.
# KEEP=1 leaves the stack running afterwards (capture-transcript.sh needs this).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
MCP_URL="$APP/mcp"
MCP_JSON_FILE=.mcp.json
EXPECTED_TOOLS_FILE=../../contracts/22-expected-tools.txt
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

# Like mcp_rpc but never fails curl on a non-2xx status: the negative witness needs the body even
# if the server answers an unknown tool with an HTTP-level error instead of a JSON-RPC one.
mcp_rpc_tolerant() {
    local headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
    if [[ -n "$MCP_SESSION_ID" ]]; then
        headers+=(-H "Mcp-Session-Id: $MCP_SESSION_ID")
    fi
    curl -s "${headers[@]}" -d "$1" "$APP/mcp"
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
tools_list_advertises_exactly_the_expected_tool_set() {
    TOOLS_LIST_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' > "$TOOLS_LIST_FILE"
    diff -q <(jq -r '.result.tools[].name' "$TOOLS_LIST_FILE" | sort) <(sort "$EXPECTED_TOOLS_FILE") >/dev/null
}

committed_mcp_json_points_at_this_server() {
    jq -e --arg u "$MCP_URL" '.mcpServers["apa-orders"].type == "http" and .mcpServers["apa-orders"].url == $u' \
        "$MCP_JSON_FILE" >/dev/null
}

ORDER_STATUS_FILE=""
order_status_tool_call_is_driveable_and_composed() {
    ORDER_STATUS_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"orderStatus","arguments":{"orderId":"O-9001"}}}' \
        > "$ORDER_STATUS_FILE"
    jq -e '.result.content[0].text | fromjson
        | (.orderStatus == "SHIPPED") and (.trackingNumber == "FL-778821")' "$ORDER_STATUS_FILE" >/dev/null
}

payment_status_tool_call_is_driveable() {
    local f; f=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"paymentStatus","arguments":{"orderId":"O-9001"}}}' \
        > "$f"
    jq -e '.result.content[0].text | fromjson | .status == "COMPLETED"' "$f" >/dev/null
}

shipment_tracking_tool_call_is_driveable() {
    local f; f=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"shipmentTracking","arguments":{"orderId":"O-9001"}}}' \
        > "$f"
    jq -e '.result.content[0].text | fromjson | .trackingNumber == "FL-778821"' "$f" >/dev/null
}

resources_list_advertises_the_playbook() {
    local f; f=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":6,"method":"resources/list","params":{}}' > "$f"
    jq -e '.result.resources[] | select(.uri == "skill://orders/status-playbook")' "$f" >/dev/null
}

prompts_list_advertises_the_summary_prompt() {
    local f; f=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":7,"method":"prompts/list","params":{}}' > "$f"
    jq -e '.result.prompts[] | select(.name == "summarizeOrderStatus")' "$f" >/dev/null
}

# The biting negative witness: deleteOrder is absent from tools/list (checked above). A consumer
# bound to the advertised surface, scripted or an interactive Claude Code session alike, has no
# way to invoke it. The call must come back rejected, either a JSON-RPC-level error or a tool
# result with isError true, never a plain successful result.
an_unadvertised_tool_cannot_be_driven() {
    local f; f=$(new_tmp_file)
    mcp_rpc_tolerant '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"deleteOrder","arguments":{"orderId":"O-9001"}}}' \
        > "$f"
    jq -e '(.error != null) or (.result.isError == true)' "$f" >/dev/null
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-22-package-api-as-mcp-tools; do
    check "$c is healthy" healthy "$c"
done

check "MCP initialize negotiates protocol revision 2025-11-25" initialize_negotiates_the_expected_protocol_revision
echo "--- advertised MCP protocol revision: ${PROTOCOL_REVISION:-unknown} ---"

check "tools/list advertises exactly orderStatus, paymentStatus and shipmentTracking" \
    tools_list_advertises_exactly_the_expected_tool_set
check "the committed .mcp.json points at this server" committed_mcp_json_points_at_this_server

check "orderStatus is callable and returns the composed, seeded result" \
    order_status_tool_call_is_driveable_and_composed
check "paymentStatus is callable" payment_status_tool_call_is_driveable
check "shipmentTracking is callable" shipment_tracking_tool_call_is_driveable

check "resources/list advertises skill://orders/status-playbook" resources_list_advertises_the_playbook
check "prompts/list advertises summarizeOrderStatus" prompts_list_advertises_the_summary_prompt

check "a tool absent from tools/list (deleteOrder) is rejected, not driveable" \
    an_unadvertised_tool_cannot_be_driven

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
