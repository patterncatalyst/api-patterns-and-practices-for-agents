#!/usr/bin/env bash
# Looks up one order's status, payment, or shipment over the apa-orders MCP server (Streamable
# HTTP), the JSON-RPC client lifecycle 22-consuming-an-mcp-server-from-claude-code's
# mcp-client.sh drives: initialize, notifications/initialized, tools/call. This is what
# SKILL.md tells an agent to run directly, and what
# 23-building-a-claude-code-skill's verify.sh exercises against the live server.
#
# Usage: order-status.sh <orderId> [operation]
# operation is one of orderStatus (default), paymentStatus, shipmentTracking; see
# ../references/order-operations.md for each one's output fields. Before calling anything, this
# script confirms the requested operation is actually advertised in the server's tools/list and
# refuses to fabricate a result for one that is not.
#
# The server URL comes from APA_ORDERS_MCP_URL (default http://localhost:8080/mcp).
set -euo pipefail

ORDER_ID="${1:?usage: order-status.sh <orderId> [operation]}"
OPERATION="${2:-orderStatus}"
MCP_URL="${APA_ORDERS_MCP_URL:-http://localhost:8080/mcp}"
MCP_SESSION_ID=""
TMP_FILES=()

cleanup() {
    rm -f "${TMP_FILES[@]}" 2>/dev/null || true
}
trap cleanup EXIT

new_tmp_file() { local f; f=$(mktemp); TMP_FILES+=("$f"); printf '%s' "$f"; }

mcp_rpc() { # mcp_rpc <json-body>: POSTs to the server with the session header (if set)
    local headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
    if [[ -n "$MCP_SESSION_ID" ]]; then
        headers+=(-H "Mcp-Session-Id: $MCP_SESSION_ID")
    fi
    curl -sf "${headers[@]}" -d "$1" "$MCP_URL"
}

mcp_initialize() { # sets MCP_SESSION_ID as a side effect
    local header_file body_file status
    header_file=$(new_tmp_file)
    body_file=$(new_tmp_file)
    status=$(curl -s -D "$header_file" -o "$body_file" -w '%{http_code}' \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"order-status.sh","version":"1.0.0"}}}' \
        "$MCP_URL")
    if [[ "$status" != "200" ]]; then
        echo "order-status.sh: initialize failed against $MCP_URL (HTTP $status); is the apa-orders server up?" >&2
        exit 1
    fi
    MCP_SESSION_ID=$(grep -i '^mcp-session-id:' "$header_file" | tr -d '\r' | cut -d' ' -f2)
    if [[ -z "$MCP_SESSION_ID" ]]; then
        echo "order-status.sh: initialize returned no Mcp-Session-Id header" >&2
        exit 1
    fi
    mcp_rpc '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
}

mcp_initialize

TOOLS_LIST=$(mcp_rpc '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}')
if ! jq -e --arg n "$OPERATION" '.result.tools[] | select(.name == $n)' <<<"$TOOLS_LIST" >/dev/null; then
    echo "order-status.sh: '$OPERATION' is not advertised by $MCP_URL; refusing to fabricate a result. Advertised operations:" >&2
    jq -r '.result.tools[].name' <<<"$TOOLS_LIST" | sed 's/^/  - /' >&2
    exit 1
fi

RESULT=$(mcp_rpc "$(printf '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"%s","arguments":{"orderId":"%s"}}}' \
    "$OPERATION" "$ORDER_ID")")

if jq -e '(.error != null) or (.result.isError == true)' <<<"$RESULT" >/dev/null; then
    echo "order-status.sh: $OPERATION rejected the call for $ORDER_ID" >&2
    jq -c '.error // .result' <<<"$RESULT" >&2
    exit 1
fi

echo "$ORDER_ID ($OPERATION):"
jq -r '.result.content[0].text | fromjson | to_entries[] | "  \(.key): \(.value)"' <<<"$RESULT"
