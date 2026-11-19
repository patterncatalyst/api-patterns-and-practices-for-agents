#!/usr/bin/env bash
# Starts the stack with the domain-split-progressive-discovery app and drives both named MCP
# servers over Streamable HTTP: the initialize handshake against each server's own root path
# (/mcp/order, /mcp/shipping), each server's small tools/list (asserted <= a degradation
# threshold, since staying small is the whole point of the split), no namespaced tool name
# colliding across servers, the shared skill://catalog resource and its resource templates, and
# two search-then-fetch round trips (a catalog-only match and a live-tool match that is then
# actually called).
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
ORDER_TOOLS_CONTRACT=../../contracts/14-order-tools.mcp.json
SHIPPING_TOOLS_CONTRACT=../../contracts/14-shipping-tools.mcp.json
CATALOG_CONTRACT=../../contracts/14-catalog.mcp.json
MAX_TOOLS_PER_SERVER=4
TOTAL=0
PASSED=0
TMP_FILES=()
ORDER_SESSION_ID=""
SHIPPING_SESSION_ID=""
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

mcp_rpc() { # mcp_rpc <server-path> <session-id> <json-body>: POSTs to <server-path>, prints the body
    local headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
    if [[ -n "$2" ]]; then
        headers+=(-H "Mcp-Session-Id: $2")
    fi
    curl -sf "${headers[@]}" -d "$3" "$APP$1"
}

mcp_initialize() { # mcp_initialize <server-path>: prints "<session-id> <protocol-revision>"
    local path="$1" header_file body_file status
    header_file=$(new_tmp_file)
    body_file=$(new_tmp_file)
    status=$(curl -s -D "$header_file" -o "$body_file" -w '%{http_code}' \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"verify-sh","version":"1.0.0"}}}' \
        "$APP$path")
    [[ "$status" == "200" ]] || return 1
    local session revision
    session=$(grep -i '^mcp-session-id:' "$header_file" | tr -d '\r' | cut -d' ' -f2)
    revision=$(jq -r '.result.protocolVersion' "$body_file")
    [[ -n "$session" ]] || return 1
    mcp_rpc "$path" "$session" '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
    printf '%s %s\n' "$session" "$revision"
}

both_servers_negotiate_the_expected_protocol_revision() {
    local order_session order_revision shipping_session shipping_revision
    read -r order_session order_revision < <(mcp_initialize /mcp/order) || return 1
    read -r shipping_session shipping_revision < <(mcp_initialize /mcp/shipping) || return 1
    ORDER_SESSION_ID="$order_session"
    SHIPPING_SESSION_ID="$shipping_session"
    PROTOCOL_REVISION="$order_revision"
    [[ "$order_revision" == "2025-11-25" && "$shipping_revision" == "2025-11-25" ]]
}

ORDER_TOOLS_LIST_FILE=""
ORDER_TOOL_COUNT=""
order_tools_list_stays_small_and_within_the_degradation_threshold() {
    ORDER_TOOLS_LIST_FILE=$(new_tmp_file)
    mcp_rpc /mcp/order "$ORDER_SESSION_ID" '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' > "$ORDER_TOOLS_LIST_FILE"
    ORDER_TOOL_COUNT=$(jq '.result.tools | length' "$ORDER_TOOLS_LIST_FILE")
    [[ "$ORDER_TOOL_COUNT" -le "$MAX_TOOLS_PER_SERVER" ]]
}

SHIPPING_TOOLS_LIST_FILE=""
SHIPPING_TOOL_COUNT=""
shipping_tools_list_stays_small_and_within_the_degradation_threshold() {
    SHIPPING_TOOLS_LIST_FILE=$(new_tmp_file)
    mcp_rpc /mcp/shipping "$SHIPPING_SESSION_ID" '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' > "$SHIPPING_TOOLS_LIST_FILE"
    SHIPPING_TOOL_COUNT=$(jq '.result.tools | length' "$SHIPPING_TOOLS_LIST_FILE")
    [[ "$SHIPPING_TOOL_COUNT" -le "$MAX_TOOLS_PER_SERVER" ]]
}

committed_order_tools_contract_matches_the_served_tools_list() {
    diff -q <(jq '.result.tools' "$ORDER_TOOLS_LIST_FILE") "$ORDER_TOOLS_CONTRACT" >/dev/null
}

committed_shipping_tools_contract_matches_the_served_tools_list() {
    diff -q <(jq '.result.tools' "$SHIPPING_TOOLS_LIST_FILE") "$SHIPPING_TOOLS_CONTRACT" >/dev/null
}

namespaced_tool_names_do_not_collide_across_servers() {
    local order_names shipping_names
    order_names=$(jq -r '.result.tools[].name' "$ORDER_TOOLS_LIST_FILE" | sort)
    shipping_names=$(jq -r '.result.tools[].name' "$SHIPPING_TOOLS_LIST_FILE" | sort)
    [[ -z "$(comm -12 <(echo "$order_names") <(echo "$shipping_names") | grep -v '^searchTools$')" ]]
}

catalog_resource_is_advertised_and_served_identically_on_both_servers() {
    local order_catalog shipping_catalog
    order_catalog=$(mcp_rpc /mcp/order "$ORDER_SESSION_ID" '{"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"skill://catalog"}}' \
        | jq '.result.contents[0].text | fromjson')
    shipping_catalog=$(mcp_rpc /mcp/shipping "$SHIPPING_SESSION_ID" '{"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"skill://catalog"}}' \
        | jq '.result.contents[0].text | fromjson')
    [[ "$order_catalog" == "$shipping_catalog" ]] && [[ "$(jq 'length' <<< "$order_catalog")" == "20" ]]
}

committed_catalog_contract_matches_the_served_catalog_resource() {
    local f; f=$(new_tmp_file)
    mcp_rpc /mcp/order "$ORDER_SESSION_ID" '{"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"skill://catalog"}}' \
        | jq '.result.contents[0].text | fromjson' > "$f"
    diff -q "$f" "$CATALOG_CONTRACT" >/dev/null
}

resource_templates_list_advertises_the_domain_and_detail_templates() {
    local templates
    templates=$(mcp_rpc /mcp/order "$ORDER_SESSION_ID" '{"jsonrpc":"2.0","id":5,"method":"resources/templates/list","params":{}}')
    jq -e '.result.resourceTemplates[] | select(.uriTemplate == "skill://{domain}/operations")' <<< "$templates" >/dev/null
    jq -e '.result.resourceTemplates[] | select(.uriTemplate == "skill://{domain}/operations/{operationId}")' <<< "$templates" >/dev/null
}

domain_operations_template_lists_ten_per_domain() {
    local order_ops shipping_ops
    order_ops=$(mcp_rpc /mcp/order "$ORDER_SESSION_ID" '{"jsonrpc":"2.0","id":6,"method":"resources/read","params":{"uri":"skill://order/operations"}}' \
        | jq '.result.contents[0].text | fromjson | length')
    shipping_ops=$(mcp_rpc /mcp/shipping "$SHIPPING_SESSION_ID" '{"jsonrpc":"2.0","id":6,"method":"resources/read","params":{"uri":"skill://shipping/operations"}}' \
        | jq '.result.contents[0].text | fromjson | length')
    [[ "$order_ops" == "10" && "$shipping_ops" == "10" ]]
}

SEARCH_REFUND_URI=""
search_then_fetch_a_catalog_only_match_returns_its_description_not_a_live_call() {
    local links
    links=$(mcp_rpc /mcp/order "$ORDER_SESSION_ID" '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"searchTools","arguments":{"query":"refund"}}}')
    [[ "$(jq '.result.content | length' <<< "$links")" == "1" ]] || return 1
    SEARCH_REFUND_URI=$(jq -r '.result.content[0].uri' <<< "$links")
    [[ "$SEARCH_REFUND_URI" == "skill://order/operations/order_refund_post" ]] || return 1
    local detail
    detail=$(mcp_rpc /mcp/order "$ORDER_SESSION_ID" "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"resources/read\",\"params\":{\"uri\":\"$SEARCH_REFUND_URI\"}}" \
        | jq '.result.contents[0].text | fromjson')
    [[ "$(jq -r '.kind' <<< "$detail")" == "catalog" ]] \
        && [[ "$(jq -r '.description' <<< "$detail" | tr '[:upper:]' '[:lower:]')" == *refund* ]]
}

SEARCH_TRACK_TOOL_NAME=""
search_then_fetch_a_live_tool_match_then_calls_it_and_gets_the_right_result() {
    local links
    links=$(mcp_rpc /mcp/shipping "$SHIPPING_SESSION_ID" '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"searchTools","arguments":{"query":"track"}}}')
    [[ "$(jq '.result.content | length' <<< "$links")" == "1" ]] || return 1
    local uri; uri=$(jq -r '.result.content[0].uri' <<< "$links")
    SEARCH_TRACK_TOOL_NAME=$(jq -r '.result.content[0].name' <<< "$links")
    [[ "$SEARCH_TRACK_TOOL_NAME" == "shipping_track_get" ]] || return 1
    local detail
    detail=$(mcp_rpc /mcp/shipping "$SHIPPING_SESSION_ID" "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"resources/read\",\"params\":{\"uri\":\"$uri\"}}" \
        | jq '.result.contents[0].text | fromjson')
    [[ "$(jq -r '.kind' <<< "$detail")" == "tool" ]] || return 1
    local called
    called=$(mcp_rpc /mcp/shipping "$SHIPPING_SESSION_ID" "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{\"name\":\"$SEARCH_TRACK_TOOL_NAME\",\"arguments\":{\"orderId\":\"O-7001\"}}}" \
        | jq '.result.content[0].text | fromjson')
    [[ "$(jq -r '.carrier' <<< "$called")" == "FASTLINE" ]] && [[ "$(jq -r '.trackingNumber' <<< "$called")" == "FL-440219" ]]
}

order_status_get_returns_the_orders_own_state_only() {
    local body
    body=$(mcp_rpc /mcp/order "$ORDER_SESSION_ID" '{"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"order_status_get","arguments":{"orderId":"O-7001"}}}' \
        | jq '.result.content[0].text | fromjson')
    [[ "$(jq -r '.status' <<< "$body")" == "SHIPPED" ]] && [[ "$(jq 'has("carrier")' <<< "$body")" == "false" ]]
}

shipping_eta_get_derives_a_plain_language_window() {
    local body
    body=$(mcp_rpc /mcp/shipping "$SHIPPING_SESSION_ID" '{"jsonrpc":"2.0","id":13,"method":"tools/call","params":{"name":"shipping_eta_get","arguments":{"orderId":"O-7001"}}}' \
        | jq '.result.content[0].text | fromjson')
    [[ "$(jq -r '.etaDescription' <<< "$body")" == "1 to 2 business days" ]]
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-domain-split-progressive-discovery; do
    check "$c is healthy" healthy "$c"
done

check "both servers' initialize negotiates protocol revision 2025-11-25" both_servers_negotiate_the_expected_protocol_revision
echo "--- advertised MCP protocol revision: ${PROTOCOL_REVISION:-unknown} ---"

check "order server tools/list stays small (<= $MAX_TOOLS_PER_SERVER)" \
    order_tools_list_stays_small_and_within_the_degradation_threshold
echo "--- order server tools/list count: ${ORDER_TOOL_COUNT:-unknown} (threshold <= $MAX_TOOLS_PER_SERVER) ---"
check "the committed contracts/14-order-tools.mcp.json matches the served tools/list" \
    committed_order_tools_contract_matches_the_served_tools_list
check "shipping server tools/list stays small (<= $MAX_TOOLS_PER_SERVER)" \
    shipping_tools_list_stays_small_and_within_the_degradation_threshold
echo "--- shipping server tools/list count: ${SHIPPING_TOOL_COUNT:-unknown} (threshold <= $MAX_TOOLS_PER_SERVER) ---"
check "the committed contracts/14-shipping-tools.mcp.json matches the served tools/list" \
    committed_shipping_tools_contract_matches_the_served_tools_list
check "namespaced tool names do not collide across the two servers" \
    namespaced_tool_names_do_not_collide_across_servers

check "skill://catalog is advertised and served identically on both servers, all 20 operations" \
    catalog_resource_is_advertised_and_served_identically_on_both_servers
check "the committed contracts/14-catalog.mcp.json matches the served catalog resource" \
    committed_catalog_contract_matches_the_served_catalog_resource
check "resources/templates/list advertises the domain-list and operation-detail templates" \
    resource_templates_list_advertises_the_domain_and_detail_templates
check "skill://{domain}/operations lists exactly 10 operations per domain, from either server" \
    domain_operations_template_lists_ten_per_domain

check "search-then-fetch: searchTools(\"refund\") resolves to a catalog-only operation, not a live call" \
    search_then_fetch_a_catalog_only_match_returns_its_description_not_a_live_call
check "search-then-fetch: searchTools(\"track\") resolves to a live tool and calling it returns the right result" \
    search_then_fetch_a_live_tool_match_then_calls_it_and_gets_the_right_result

check "order_status_get returns the order's own state with no shipment field" \
    order_status_get_returns_the_orders_own_state_only
check "shipping_eta_get derives a plain-language window from the shipment's lifecycle status" \
    shipping_eta_get_derives_a_plain_language_window

# The negative witness (demonstrated live, then reverted; see README and
# _plans/verification-log.md): promoting the catalog's 16 catalog-only operations to live
# @Tool methods on one merged server (dropping the split) pushes that server's tools/list count
# from 3 to 19, well past MAX_TOOLS_PER_SERVER, failing the degradation-threshold checks above.

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
