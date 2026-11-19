#!/usr/bin/env bash
# Starts the stack with the mcp-first-single-bean app and checks that the same OrderOperations
# business method, called three ways (REST GET, a GraphQL query, and an MCP tools/call over
# Streamable HTTP), returns identical business results. Also asserts the MCP tools/list
# advertisement shape and records the negotiated MCP protocol revision.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
CONTRACT_FILE=../../contracts/12-order-tools.mcp.json
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
tools_list_advertises_order_summary_with_a_schema() {
    TOOLS_LIST_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' > "$TOOLS_LIST_FILE"
    jq -e '.result.tools[] | select(.name == "orderSummary") | has("inputSchema")' "$TOOLS_LIST_FILE" >/dev/null
}

committed_mcp_contract_matches_the_served_tools_list() {
    diff -q <(jq '.result.tools' "$TOOLS_LIST_FILE") "$CONTRACT_FILE" >/dev/null
}

REST_FILE=""
GRAPHQL_FILE=""
MCP_CALL_FILE=""

rest_call_succeeds() {
    REST_FILE=$(new_tmp_file)
    curl -sf -o "$REST_FILE" "$APP/orders/O-9001/summary"
}

graphql_call_succeeds() {
    GRAPHQL_FILE=$(new_tmp_file)
    curl -sf -H 'Content-Type: application/json' \
        -d '{"query":"{ orderSummary(orderId: \"O-9001\") { orderId customerId itemSku quantity amount status } }"}' \
        "$APP/graphql" -o "$GRAPHQL_FILE"
    jq -e '.data.orderSummary.orderId == "O-9001"' "$GRAPHQL_FILE" >/dev/null
}

mcp_tool_call_succeeds() {
    MCP_CALL_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"orderSummary","arguments":{"orderId":"O-9001"}}}' \
        > "$MCP_CALL_FILE"
    jq -e '.result.content[0].text | fromjson | .orderId == "O-9001"' "$MCP_CALL_FILE" >/dev/null
}

rest_graphql_and_mcp_return_identical_business_results() {
    local rest_json graphql_json mcp_json
    rest_json=$(jq -S '{orderId, customerId, itemSku, quantity, amount, status}' "$REST_FILE")
    graphql_json=$(jq -S '.data.orderSummary | {orderId, customerId, itemSku, quantity, amount, status}' "$GRAPHQL_FILE")
    mcp_json=$(jq -S '.result.content[0].text | fromjson | {orderId, customerId, itemSku, quantity, amount, status}' \
        "$MCP_CALL_FILE")
    [[ "$rest_json" == "$graphql_json" ]] && [[ "$rest_json" == "$mcp_json" ]]
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-mcp-first-single-bean; do
    check "$c is healthy" healthy "$c"
done

check "MCP initialize negotiates protocol revision 2025-11-25" initialize_negotiates_the_expected_protocol_revision
echo "--- advertised MCP protocol revision: ${PROTOCOL_REVISION:-unknown} ---"
check "MCP tools/list advertises a tool named orderSummary with an inputSchema" \
    tools_list_advertises_order_summary_with_a_schema
check "the committed contracts/12-order-tools.mcp.json matches the served tools/list" \
    committed_mcp_contract_matches_the_served_tools_list

check "the REST face returns the seeded order summary" rest_call_succeeds
check "the GraphQL face returns the seeded order summary" graphql_call_succeeds
check "the MCP tools/call face returns the seeded order summary" mcp_tool_call_succeeds
check "REST, GraphQL and MCP all return byte-identical business results for O-9001" \
    rest_graphql_and_mcp_return_identical_business_results

# The negative witness: reimplementing one face independently of the shared OrderOperations bean
# (instead of delegating to it) must make the cross-face equality check above fail. See README
# and _plans/verification-log.md for the bite that confirms it.

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
