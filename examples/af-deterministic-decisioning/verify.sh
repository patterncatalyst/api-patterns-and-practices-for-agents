#!/usr/bin/env bash
# Starts the stack with the af-deterministic-decisioning service and drives its one MCP tool,
# reviewRefund, over Streamable HTTP: the initialize handshake, tools/list (confirming the tool is
# advertised), then two tools/call reviews, a compliant refund proposal and an over-limit one. The
# verdict for each is a pure function of the committed Drools ruleset (rules/refund-review.drl,
# compiled once at startup by RefundReviewer with KieHelper), never a model call: the compliant
# proposal is approved, the over-limit one is rejected naming amount-exceeds-paid, and every
# verdict is recorded durably in PostgreSQL. The negative witness points RefundReviewer at the
# weakened ruleset (the same rules with the amount-ceiling rule removed, via the
# REVIEW_RULE_RESOURCE demo-code override) and replays the SAME over-limit proposal: it is wrongly
# approved, proving that rule is exactly what caught it. The real ruleset is then restored and
# re-asserted.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
SVC=af-deterministic-decisioning
REAL_RULES=rules/refund-review.drl
WEAKENED_RULES=rules/refund-review-weakened.drl
TOTAL=0
PASSED=0
TMP_FILES=()
MCP_SESSION_ID=""

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

poll() { # poll <seconds> <command...>: retry every 3 s until the command succeeds
    local end=$((SECONDS + $1)); shift
    until "$@"; do
        (( SECONDS < end )) || return 1
        sleep 3
    done
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

mcp_initialize() { # sets MCP_SESSION_ID as a side effect
    local header_file body_file status
    header_file=$(new_tmp_file)
    body_file=$(new_tmp_file)
    status=$(curl -s -D "$header_file" -o "$body_file" -w '%{http_code}' \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"verify-sh","version":"1.0.0"}}}' \
        "$APP/mcp")
    [[ "$status" == "200" ]] || return 1
    MCP_SESSION_ID=$(grep -i '^mcp-session-id:' "$header_file" | tr -d '\r' | cut -d' ' -f2)
    [[ -n "$MCP_SESSION_ID" ]] || return 1
    mcp_rpc '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
}

tools_list_advertises_review_refund() {
    mcp_rpc '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
        | jq -e '.result.tools[] | select(.name == "reviewRefund") | has("inputSchema")' >/dev/null
}

CALL_RESULT_FILE=""
review_refund() { # review_refund <orderId> <customerId> <orderStatus> <paidAmount> <refundAmount> <reason>
    CALL_RESULT_FILE=$(new_tmp_file)
    local args
    args=$(jq -n --arg o "$1" --arg c "$2" --arg s "$3" --arg p "$4" --arg r "$5" --arg why "$6" \
        '{orderId:$o, customerId:$c, orderStatus:$s, orderPaidAmount:($p|tonumber), refundAmount:($r|tonumber), reason:$why}')
    mcp_rpc "$(jq -n --arg a "$args" \
        '{jsonrpc:"2.0", id:3, method:"tools/call", params:{name:"reviewRefund", arguments:($a|fromjson)}}')" \
        > "$CALL_RESULT_FILE"
}

tool_call_decision_is() { # tool_call_decision_is <decision>: checks the just-made review_refund result
    jq -e --arg d "$1" '.result.content[0].text | fromjson | .decision == $d' "$CALL_RESULT_FILE" >/dev/null
}

review_column() { # review_column <column> <orderId>
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT $1 FROM refunds.reviews WHERE order_id='$2' ORDER BY reviewed_at DESC LIMIT 1"
}
verdict_is() { [[ "$(review_column verdict "$1")" == "$2" ]]; } # verdict_is <orderId> <verdict>
failed_clause_is() { [[ "$(review_column failed_clause "$1")" == "$2" ]]; } # failed_clause_is <orderId> <clause>
clear_reviews() {
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -c \
        "DELETE FROM refunds.reviews" >/dev/null
}

prom_has_metric() {
    curl -sf -G "http://localhost:19090/api/v1/query" --data-urlencode "query=target_info{service_name=\"$SVC\"}" \
        | grep '"result":\[{' >/dev/null
}
prom_has_counter() {
    curl -sf "http://localhost:19090/api/v1/label/__name__/values" | grep -i 'afdeterministicdecisioning' >/dev/null
}
loki_has_log() { # loki_has_log <search string>
    local now; now=$(date +%s)
    curl -sf -G "http://localhost:3100/loki/api/v1/query_range" --data-urlencode "query={service_name=\"$SVC\"} |= \"$1\"" \
        --data-urlencode "start=$((now - 600))000000000" --data-urlencode "end=$((now + 60))000000000" \
        | grep '"values":\[\[' >/dev/null
}

restart_app() { "${COMPOSE[@]}" up -d --no-deps --force-recreate --wait app >/dev/null; }

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-af-deterministic-decisioning; do
    check "$c is healthy" healthy "$c"
done

check "MCP initialize succeeds" mcp_initialize
check "tools/list advertises reviewRefund with an inputSchema" tools_list_advertises_review_refund

echo "Compliant proposal: the committed Drools ruleset approves it"
review_refund O-AF2001 CUST-AF2001 PAID 200.00 150.00 DAMAGED_IN_TRANSIT
check "reviewRefund returns decision=APPROVE" tool_call_decision_is APPROVE
check "the compliant proposal O-AF2001 is recorded APPROVE (observed in PostgreSQL, not model prose)" \
    poll 60 verdict_is O-AF2001 APPROVE

echo "Over-limit proposal: the ruleset rejects it and names the clause that failed"
review_refund O-AF2002 CUST-AF2002 PAID 200.00 500.00 DAMAGED_IN_TRANSIT
check "reviewRefund returns decision=REJECT naming amount-exceeds-paid" tool_call_decision_is REJECT
check "the over-limit proposal O-AF2002 is recorded REJECT" poll 60 verdict_is O-AF2002 REJECT
check "the recorded REJECT names the amount-ceiling clause" failed_clause_is O-AF2002 amount-exceeds-paid

# No Tempo check: quarkus-mcp-server-http's Streamable HTTP endpoint is a raw Vert.x route, not
# auto-instrumented for distributed tracing the way a Camel or quarkus-rest route is (confirmed
# live: Prometheus and Loki both receive signals from this service, Tempo receives none from any
# service in this stack while only MCP calls have been made). ch13 (the other MCP-tool-only
# example, no Camel route) holds the same line and checks no Tempo signal either.
check "Prometheus has a metric from $SVC" poll 90 prom_has_metric
check "Prometheus has the review-recorded counter" poll 90 prom_has_counter
check "Loki has the recorder's own log line (observed, not model prose)" poll 90 loki_has_log "review recorded"

echo "Weakened-ruleset negative witness: the amount-ceiling rule is exactly what caught it"
clear_reviews
REVIEW_RULE_RESOURCE=$WEAKENED_RULES restart_app
MCP_SESSION_ID=""
mcp_initialize
review_refund O-AF2002 CUST-AF2002 PAID 200.00 500.00 DAMAGED_IN_TRANSIT
check "with the weakened ruleset (no amount-ceiling rule), the SAME over-limit proposal is wrongly approved" \
    poll 60 verdict_is O-AF2002 APPROVE

echo "Restore: the real ruleset is back, the over-limit proposal is rejected again"
clear_reviews
REVIEW_RULE_RESOURCE=$REAL_RULES restart_app
MCP_SESSION_ID=""
mcp_initialize
review_refund O-AF2002 CUST-AF2002 PAID 200.00 500.00 DAMAGED_IN_TRANSIT
check "restored: the real ruleset rejects O-AF2002 again, naming the same clause" \
    poll 60 failed_clause_is O-AF2002 amount-exceeds-paid

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
