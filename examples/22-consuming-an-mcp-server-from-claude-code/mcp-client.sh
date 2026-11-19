#!/usr/bin/env bash
# A standalone, deterministic JSON-RPC MCP client: the scripted stand-in for what an interactive
# Claude Code session does the moment a reader points it at ch13's MCP server through .mcp.json.
# It runs the full client lifecycle against an already-running server: initialize, the
# notifications/initialized handshake, tools/list, resources/list, prompts/list, a tools/call for
# each advertised tool against the seeded order O-9001, a resources/read of the playbook, and a
# prompts/get of summarizeOrderStatus. It prints a readable summary of what the server advertised
# and returned; it never starts or stops the stack.
#
# Usage: ./mcp-client.sh [server-base-url]
# The server base URL (no trailing /mcp) can also be set with MCP_SERVER_URL. Defaults to the
# compose-published ch13 server: http://localhost:8080.
set -euo pipefail

SERVER_URL="${1:-${MCP_SERVER_URL:-http://localhost:8080}}"
MCP_URL="$SERVER_URL/mcp"
TMP_FILES=()
MCP_SESSION_ID=""
PROTOCOL_REVISION=""

cleanup() {
    rm -f "${TMP_FILES[@]}" 2>/dev/null || true
}
trap cleanup EXIT

new_tmp_file() { local f; f=$(mktemp); TMP_FILES+=("$f"); printf '%s' "$f"; }

heading() { printf '\n== %s ==\n' "$1"; }

mcp_rpc() { # mcp_rpc <json-body>: POSTs to /mcp with the session header (if set), prints the body
    local headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
    if [[ -n "$MCP_SESSION_ID" ]]; then
        headers+=(-H "Mcp-Session-Id: $MCP_SESSION_ID")
    fi
    curl -sf "${headers[@]}" -d "$1" "$MCP_URL"
}

mcp_initialize() { # sets MCP_SESSION_ID and PROTOCOL_REVISION as a side effect
    local header_file body_file status
    header_file=$(new_tmp_file)
    body_file=$(new_tmp_file)
    status=$(curl -s -D "$header_file" -o "$body_file" -w '%{http_code}' \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"mcp-client.sh","version":"1.0.0"}}}' \
        "$MCP_URL")
    if [[ "$status" != "200" ]]; then
        echo "initialize failed against $MCP_URL (HTTP $status); is the ch13 server up? Try ./verify.sh or docker compose up." >&2
        exit 1
    fi
    MCP_SESSION_ID=$(grep -i '^mcp-session-id:' "$header_file" | tr -d '\r' | cut -d' ' -f2)
    PROTOCOL_REVISION=$(jq -r '.result.protocolVersion' "$body_file")
    if [[ -z "$MCP_SESSION_ID" ]]; then
        echo "initialize returned no Mcp-Session-Id header" >&2
        exit 1
    fi
    mcp_rpc '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
}

call_tool() { # call_tool <id> <name> <orderId>: prints the composed JSON-RPC result
    local id="$1" name="$2" order_id="$3" out
    out=$(mcp_rpc "$(printf '{"jsonrpc":"2.0","id":%s,"method":"tools/call","params":{"name":"%s","arguments":{"orderId":"%s"}}}' \
        "$id" "$name" "$order_id")")
    heading "tools/call $name(orderId=$order_id)"
    jq -r '.result.content[0].text | fromjson' <<<"$out"
}

echo "Connecting to $MCP_URL"

mcp_initialize
heading "initialize"
echo "Negotiated protocol revision: $PROTOCOL_REVISION"
echo "Session: $MCP_SESSION_ID"

TOOLS_LIST=$(mcp_rpc '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}')
heading "tools/list"
jq -r '.result.tools[] | "- \(.name): \(.description)"' <<<"$TOOLS_LIST"

RESOURCES_LIST=$(mcp_rpc '{"jsonrpc":"2.0","id":3,"method":"resources/list","params":{}}')
heading "resources/list"
jq -r '.result.resources[] | "- \(.uri): \(.name)"' <<<"$RESOURCES_LIST"

PROMPTS_LIST=$(mcp_rpc '{"jsonrpc":"2.0","id":4,"method":"prompts/list","params":{}}')
heading "prompts/list"
jq -r '.result.prompts[] | "- \(.name): \(.description)"' <<<"$PROMPTS_LIST"

call_tool 5 orderStatus O-9001
call_tool 6 paymentStatus O-9001
call_tool 7 shipmentTracking O-9001

RESOURCE_READ=$(mcp_rpc '{"jsonrpc":"2.0","id":8,"method":"resources/read","params":{"uri":"skill://orders/status-playbook"}}')
heading "resources/read skill://orders/status-playbook"
jq -r '.result.contents[0].text' <<<"$RESOURCE_READ"

PROMPT_GET=$(mcp_rpc '{"jsonrpc":"2.0","id":9,"method":"prompts/get","params":{"name":"summarizeOrderStatus","arguments":{"orderId":"O-9001"}}}')
heading "prompts/get summarizeOrderStatus(orderId=O-9001)"
jq -r '.result.messages[0].content.text' <<<"$PROMPT_GET"

TOOL_COUNT=$(jq '.result.tools | length' <<<"$TOOLS_LIST")
RESOURCE_COUNT=$(jq '.result.resources | length' <<<"$RESOURCES_LIST")
PROMPT_COUNT=$(jq '.result.prompts | length' <<<"$PROMPTS_LIST")
heading "summary"
printf 'Discovered %s tool(s), %s resource(s), %s prompt(s); every tools/call, resources/read and prompts/get above succeeded.\n' \
    "$TOOL_COUNT" "$RESOURCE_COUNT" "$PROMPT_COUNT"
