#!/usr/bin/env bash
# Starts the stack with the securing-gating-mcp-tools app and drives OIDC bearer auth over /mcp:
# a call with no bearer token is rejected before the MCP dispatcher runs; a token scoped to only
# mcp:tools:read can call the role-gated read tool (orderSummary) but is rejected by the
# scope-gated write tool (cancelOrder), leaving the order row unchanged; and a token scoped to
# mcp:tools:write clears that same scope gate and reaches cancelOrder's Elicitation confirm (see
# the trailing comment for why the confirm round trip itself is left to the Quarkus
# @TestSecurity suite, not this script). Tokens come from the r02 `apa` Keycloak realm
# (examples/_infra/keycloak/realm-apa.json), reused unchanged, via its apa-mcp client_credentials
# (service account) client.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
KEYCLOAK_TOKEN_URL=http://localhost:8180/realms/apa/protocol/openid-connect/token
CLIENT_ID=apa-mcp
CLIENT_SECRET=apa-mcp-dev-secret
TOOLS_CONTRACT=../../contracts/16-order-tools.mcp.json
TOTAL=0
PASSED=0
TMP_FILES=()
MCP_SESSION_ID=""
PROTOCOL_REVISION=""
READ_TOKEN=""
WRITE_TOKEN=""

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

fetch_token() { # fetch_token <scope>: prints a client_credentials access token on stdout
    curl -sf -X POST "$KEYCLOAK_TOKEN_URL" \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        -d "grant_type=client_credentials&client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&scope=$1" \
        | jq -r '.access_token'
}

mcp_rpc() { # mcp_rpc <token> <json-body>: POSTs to /mcp with the bearer token and session header (if set)
    local headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -H "Authorization: Bearer $1")
    if [[ -n "$MCP_SESSION_ID" ]]; then
        headers+=(-H "Mcp-Session-Id: $MCP_SESSION_ID")
    fi
    curl -sf "${headers[@]}" -d "$2" "$APP/mcp"
}

mcp_initialize() { # mcp_initialize <token>: sets MCP_SESSION_ID and PROTOCOL_REVISION as a side effect
    local header_file body_file status
    header_file=$(new_tmp_file)
    body_file=$(new_tmp_file)
    status=$(curl -s -D "$header_file" -o "$body_file" -w '%{http_code}' \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -H "Authorization: Bearer $1" \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{"elicitation":{}},"clientInfo":{"name":"verify-sh","version":"1.0.0"}}}' \
        "$APP/mcp")
    [[ "$status" == "200" ]] || return 1
    MCP_SESSION_ID=$(grep -i '^mcp-session-id:' "$header_file" | tr -d '\r' | cut -d' ' -f2)
    PROTOCOL_REVISION=$(jq -r '.result.protocolVersion' "$body_file")
    [[ -n "$MCP_SESSION_ID" ]] || return 1
    mcp_rpc "$1" '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
}

unauthenticated_mcp_call_is_rejected() {
    local status
    status=$(curl -s -o /dev/null -w '%{http_code}' \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{"elicitation":{}},"clientInfo":{"name":"verify-sh","version":"1.0.0"}}}' \
        "$APP/mcp")
    [[ "$status" == "401" ]]
}

fetch_read_and_write_tokens() {
    READ_TOKEN=$(fetch_token "mcp:tools:read")
    WRITE_TOKEN=$(fetch_token "mcp:tools:write")
    [[ -n "$READ_TOKEN" && "$READ_TOKEN" != "null" && -n "$WRITE_TOKEN" && "$WRITE_TOKEN" != "null" ]]
}

TOOLS_LIST_FILE=""
tools_list_advertises_both_tools_with_schemas() {
    mcp_initialize "$READ_TOKEN"
    TOOLS_LIST_FILE=$(new_tmp_file)
    mcp_rpc "$READ_TOKEN" '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' > "$TOOLS_LIST_FILE"
    for name in orderSummary cancelOrder; do
        jq -e --arg n "$name" '.result.tools[] | select(.name == $n) | has("inputSchema")' "$TOOLS_LIST_FILE" >/dev/null
    done
}

committed_tools_contract_matches_the_served_tools_list() {
    diff -q <(jq '.result.tools' "$TOOLS_LIST_FILE") "$TOOLS_CONTRACT" >/dev/null
}

read_scoped_token_can_call_the_read_tool() {
    local f; f=$(new_tmp_file)
    mcp_rpc "$READ_TOKEN" '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"orderSummary","arguments":{"orderId":"O-9001"}}}' \
        > "$f"
    jq -e '.result.content[0].text | fromjson | .orderId == "O-9001" and .status == "PLACED"' "$f" >/dev/null
}

read_scoped_token_cannot_reach_the_write_tool() {
    local f; f=$(new_tmp_file)
    mcp_rpc "$READ_TOKEN" \
        '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"cancelOrder","arguments":{"orderId":"O-9001","reason":"under-scoped probe"}}}' \
        > "$f" 2>/dev/null || true
    # A scope failure comes back either as an MCP tool error (isError true) or as curl -f failing
    # outright on a non-2xx HTTP status (leaving $f empty); either way there is no cancelOrder
    # success envelope (an "outcome" field) to find.
    ! jq -e '.result.content[0].text | fromjson | .outcome' "$f" >/dev/null 2>&1
}

order_o9001_is_still_placed_after_the_under_scoped_attempt() {
    local f; f=$(new_tmp_file)
    mcp_rpc "$READ_TOKEN" '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"orderSummary","arguments":{"orderId":"O-9001"}}}' \
        > "$f"
    jq -e '.result.content[0].text | fromjson | .status == "PLACED"' "$f" >/dev/null
}

write_scoped_token_clears_the_scope_gate_and_reaches_the_elicitation_wait() {
    mcp_initialize "$WRITE_TOKEN"
    # Once the scope check passes, cancelOrder blocks on Elicitation.requestBuilder()...
    # sendAndAwait(): a server-initiated elicitation/create request over the same Streamable HTTP
    # response stream, which this plain curl has no client loop to answer. A quick reply here
    # would mean the scope check rejected the call before reaching the confirm step, so the PASS
    # signal is curl's own --max-time timeout (exit code 28), not a response body.
    local status=0
    curl -s --max-time 5 \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -H "Authorization: Bearer $WRITE_TOKEN" -H "Mcp-Session-Id: $MCP_SESSION_ID" \
        -d '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"cancelOrder","arguments":{"orderId":"O-9002","reason":"customer requested"}}}' \
        "$APP/mcp" >/dev/null || status=$?
    [[ "$status" -eq 28 ]]
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-securing-gating-mcp-tools; do
    check "$c is healthy" healthy "$c"
done

check "an unauthenticated call to /mcp is rejected (401)" unauthenticated_mcp_call_is_rejected
check "a mcp:tools:read token and a mcp:tools:write token can both be fetched from the apa realm" \
    fetch_read_and_write_tokens

check "tools/list advertises orderSummary and cancelOrder with inputSchemas" \
    tools_list_advertises_both_tools_with_schemas
echo "--- advertised MCP protocol revision: ${PROTOCOL_REVISION:-unknown} ---"
check "the committed contracts/16-order-tools.mcp.json matches the served tools/list" \
    committed_tools_contract_matches_the_served_tools_list

check "a mcp:tools:read-scoped token can call the role-gated read tool" \
    read_scoped_token_can_call_the_read_tool
check "a mcp:tools:read-scoped token cannot reach the scope-gated write tool" \
    read_scoped_token_cannot_reach_the_write_tool
check "order O-9001 is still PLACED after the under-scoped cancelOrder attempt" \
    order_o9001_is_still_placed_after_the_under_scoped_attempt

check "a mcp:tools:write-scoped token clears the scope gate and reaches the Elicitation confirm" \
    write_scoped_token_clears_the_scope_gate_and_reaches_the_elicitation_wait

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]

# The negative witness (for the orchestrator's live bite, not run here): remove requireWriteScope()
# (or its call) from OrderMcpTools.cancelOrder so a mcp:tools:read-scoped token can reach the
# mutation. With that change in place, "a mcp:tools:read-scoped token cannot reach the scope-gated
# write tool" and "order O-9001 is still PLACED after the under-scoped cancelOrder attempt" must
# both fail: the under-scoped call now clears straight through to Elicitation.sendAndAwait() (and,
# if answered, to the cancellation itself), exactly like a correctly-scoped call. Restoring the
# scope check returns both checks to PASS. Record the bite in _plans/verification-log.md.
