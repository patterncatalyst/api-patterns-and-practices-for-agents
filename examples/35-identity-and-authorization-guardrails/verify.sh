#!/usr/bin/env bash
# Starts the stack with the identity-and-authorization-guardrails app and drives the full
# identity-guardrail story over /mcp: a call with no bearer token is rejected before the MCP
# dispatcher runs; a low-assurance, read-scoped token can call the read tool (orderSummary) but is
# rejected by the destructive tool (cancelOrder), leaving the target order unchanged; a real RFC
# 8693 token exchange trades that same read token for a write-scoped token whose acr claim carries
# the step-up assurance level; that exchanged token clears both the scope gate and the acr floor
# and the destructive op proceeds. A token exchanged for the step-up acr alone, without the write
# scope, is still rejected: the two checks are independent gates. The negative witness is wired
# into the run itself: the app is recreated with STEPUP_ENFORCED=false, a write-scoped but
# never-stepped-up token now reaches the mutation it could not reach before, and the app is then
# recreated again with the floor restored and reverified green.
# Tokens come from the `apa` realm (examples/_infra/keycloak/realm-apa.json): the read token and
# the exchange both go through the `apa-agent-bff` confidential, token-exchange-enabled client; the
# bite uses a plain `apa-mcp` client_credentials token, which never carries an acr claim.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
KEYCLOAK_TOKEN_URL=http://localhost:8180/realms/apa/protocol/openid-connect/token
AGENT_BFF_CLIENT_ID=apa-agent-bff
AGENT_BFF_CLIENT_SECRET=apa-agent-bff-dev-secret
MCP_CLIENT_ID=apa-mcp
MCP_CLIENT_SECRET=apa-mcp-dev-secret
TOOLS_CONTRACT=../../contracts/35-order-tools.mcp.json
TOTAL=0
PASSED=0
TMP_FILES=()
MCP_SESSION_ID=""
PROTOCOL_REVISION=""
READ_TOKEN=""
WRITE_STEPUP_TOKEN=""
HIGH_ACR_NO_SCOPE_TOKEN=""
WRITE_LOW_ACR_TOKEN=""

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

fetch_token() { # fetch_token <client_id> <client_secret> <scope>: prints an access token on stdout
    curl -sf -X POST "$KEYCLOAK_TOKEN_URL" \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        -d "grant_type=client_credentials&client_id=$1&client_secret=$2&scope=$3" \
        | jq -r '.access_token'
}

exchange_token() { # exchange_token <subject_token> <scope>: prints the exchanged access token
    curl -sf -X POST "$KEYCLOAK_TOKEN_URL" \
        -H 'Content-Type: application/x-www-form-urlencoded' \
        --data-urlencode "grant_type=urn:ietf:params:oauth:grant-type:token-exchange" \
        --data-urlencode "client_id=$AGENT_BFF_CLIENT_ID" \
        --data-urlencode "client_secret=$AGENT_BFF_CLIENT_SECRET" \
        --data-urlencode "subject_token=$1" \
        --data-urlencode "subject_token_type=urn:ietf:params:oauth:token-type:access_token" \
        --data-urlencode "requested_token_type=urn:ietf:params:oauth:token-type:access_token" \
        --data-urlencode "audience=apa-mcp" \
        --data-urlencode "scope=$2" \
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
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"verify-sh","version":"1.0.0"}}}' \
        "$APP/mcp")
    [[ "$status" == "200" ]] || return 1
    MCP_SESSION_ID=$(grep -i '^mcp-session-id:' "$header_file" | tr -d '\r' | cut -d' ' -f2)
    PROTOCOL_REVISION=$(jq -r '.result.protocolVersion' "$body_file")
    [[ -n "$MCP_SESSION_ID" ]] || return 1
    mcp_rpc "$1" '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
}

# Each call below re-initializes its own MCP session for the given token: a Streamable HTTP
# session is bound to the identity it was initialized under, so reusing a session id across two
# different tokens would mix identities instead of cleanly isolating each check.
order_status() { # order_status <token> <order-id>: prints the order's status via orderSummary
    MCP_SESSION_ID=""
    mcp_initialize "$1" || return 1
    local f; f=$(new_tmp_file)
    mcp_rpc "$1" "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"orderSummary\",\"arguments\":{\"orderId\":\"$2\"}}}" \
        > "$f"
    jq -r '.result.content[0].text | fromjson | .status' "$f"
}

cancel_order_mutates() { # cancel_order_mutates <token> <order-id>: true if the cancel actually succeeded
    MCP_SESSION_ID=""
    mcp_initialize "$1" || return 1
    local f; f=$(new_tmp_file)
    mcp_rpc "$1" "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"cancelOrder\",\"arguments\":{\"orderId\":\"$2\",\"reason\":\"verify.sh\"}}}" \
        > "$f" 2>/dev/null || true
    jq -e '.result.content[0].text | fromjson | .outcome == "CANCELLED"' "$f" >/dev/null 2>&1
}

unauthenticated_mcp_call_is_rejected() {
    local status
    status=$(curl -s -o /dev/null -w '%{http_code}' \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"verify-sh","version":"1.0.0"}}}' \
        "$APP/mcp")
    [[ "$status" == "401" ]]
}

fetch_low_assurance_read_token() {
    READ_TOKEN=$(fetch_token "$AGENT_BFF_CLIENT_ID" "$AGENT_BFF_CLIENT_SECRET" "mcp:tools:read")
    [[ -n "$READ_TOKEN" && "$READ_TOKEN" != "null" ]]
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

low_assurance_token_can_call_the_read_tool() {
    [[ "$(order_status "$READ_TOKEN" O-9101)" == "PLACED" ]]
}

low_assurance_token_cannot_reach_the_destructive_tool() {
    ! cancel_order_mutates "$READ_TOKEN" O-9102
}

order_o9102_is_still_placed_after_the_low_assurance_attempt() {
    [[ "$(order_status "$READ_TOKEN" O-9102)" == "PLACED" ]]
}

real_token_exchange_yields_a_write_scoped_step_up_token() {
    WRITE_STEPUP_TOKEN=$(exchange_token "$READ_TOKEN" "mcp:tools:write step-up-acr")
    [[ -n "$WRITE_STEPUP_TOKEN" && "$WRITE_STEPUP_TOKEN" != "null" ]]
}

exchanged_token_clears_both_gates_and_cancels_the_order() {
    cancel_order_mutates "$WRITE_STEPUP_TOKEN" O-9105
}

order_o9105_is_now_cancelled() {
    [[ "$(order_status "$WRITE_STEPUP_TOKEN" O-9105)" == "CANCELLED" ]]
}

step_up_acr_alone_without_the_write_scope_is_still_rejected() {
    HIGH_ACR_NO_SCOPE_TOKEN=$(exchange_token "$READ_TOKEN" "step-up-acr")
    [[ -n "$HIGH_ACR_NO_SCOPE_TOKEN" && "$HIGH_ACR_NO_SCOPE_TOKEN" != "null" ]] || return 1
    ! cancel_order_mutates "$HIGH_ACR_NO_SCOPE_TOKEN" O-9103
}

order_o9103_is_still_placed_after_the_missing_scope_attempt() {
    [[ "$(order_status "$READ_TOKEN" O-9103)" == "PLACED" ]]
}

recreate_app_with_step_up_disabled() {
    STEPUP_ENFORCED=false "${COMPOSE[@]}" up -d --force-recreate --no-deps --wait app
}

write_scoped_low_acr_token_now_reaches_the_mutation() {
    WRITE_LOW_ACR_TOKEN=$(fetch_token "$MCP_CLIENT_ID" "$MCP_CLIENT_SECRET" "mcp:tools:write")
    [[ -n "$WRITE_LOW_ACR_TOKEN" && "$WRITE_LOW_ACR_TOKEN" != "null" ]] || return 1
    cancel_order_mutates "$WRITE_LOW_ACR_TOKEN" O-9102
}

order_o9102_is_now_cancelled_by_the_bite() {
    [[ "$(order_status "$WRITE_LOW_ACR_TOKEN" O-9102)" == "CANCELLED" ]]
}

restore_app_with_step_up_enforced() {
    STEPUP_ENFORCED=true "${COMPOSE[@]}" up -d --force-recreate --no-deps --wait app
}

write_scoped_low_acr_token_is_rejected_again() {
    local token
    token=$(fetch_token "$MCP_CLIENT_ID" "$MCP_CLIENT_SECRET" "mcp:tools:write")
    [[ -n "$token" && "$token" != "null" ]] || return 1
    ! cancel_order_mutates "$token" O-9103
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-identity-and-authorization-guardrails; do
    check "$c is healthy" healthy "$c"
done

check "an unauthenticated call to /mcp is rejected (401)" unauthenticated_mcp_call_is_rejected
check "a low-assurance, read-scoped token can be fetched from the apa realm via apa-agent-bff" \
    fetch_low_assurance_read_token

check "tools/list advertises orderSummary and cancelOrder with inputSchemas" \
    tools_list_advertises_both_tools_with_schemas
echo "--- advertised MCP protocol revision: ${PROTOCOL_REVISION:-unknown} ---"
check "the committed contracts/35-order-tools.mcp.json matches the served tools/list" \
    committed_tools_contract_matches_the_served_tools_list

check "a low-assurance token can call the role-gated read tool" \
    low_assurance_token_can_call_the_read_tool
check "a low-assurance token cannot reach the destructive tool" \
    low_assurance_token_cannot_reach_the_destructive_tool
check "order O-9102 is still PLACED after the low-assurance attempt" \
    order_o9102_is_still_placed_after_the_low_assurance_attempt

check "a real RFC 8693 token exchange yields a write-scoped, step-up acr token" \
    real_token_exchange_yields_a_write_scoped_step_up_token
check "the exchanged token clears both the scope gate and the acr floor and cancels the order" \
    exchanged_token_clears_both_gates_and_cancels_the_order
check "order O-9105 is now CANCELLED" \
    order_o9105_is_now_cancelled

check "a token exchanged for the step-up acr alone, without the write scope, is still rejected" \
    step_up_acr_alone_without_the_write_scope_is_still_rejected
check "order O-9103 is still PLACED after the missing-scope attempt" \
    order_o9103_is_still_placed_after_the_missing_scope_attempt

# The biting negative witness, wired into this run: recreate the app with STEPUP_ENFORCED=false
# (the write-scope check is untouched by the flag) and watch a write-scoped token that was never
# stepped up reach the mutation the acr floor existed to stop.
check "recreating the app with STEPUP_ENFORCED=false succeeds" recreate_app_with_step_up_disabled
check "BITE: with step-up disabled, a write-scoped, low-acr token now reaches the mutation" \
    write_scoped_low_acr_token_now_reaches_the_mutation
check "BITE: order O-9102 is now CANCELLED, which the acr floor exists to prevent" \
    order_o9102_is_now_cancelled_by_the_bite

check "restoring the app with STEPUP_ENFORCED=true succeeds" restore_app_with_step_up_enforced
check "RESTORED: the same write-scoped, low-acr token is rejected again" \
    write_scoped_low_acr_token_is_rejected_again

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
