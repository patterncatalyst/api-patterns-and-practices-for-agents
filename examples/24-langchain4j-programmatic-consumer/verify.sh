#!/usr/bin/env bash
# Starts the stack with ch13's package-api-as-mcp-tools server and this chapter's consumer, and
# checks that POST /consume/<orderId> makes OrderStatusConsumerAgent (a @RegisterAiService method
# annotated @McpToolBox("apa-orders")) call the REMOTE orderStatus tool over Streamable HTTP MCP,
# then call its one local tool, OrderStatusRecorder, which writes a durable
# orders.agent_order_status_consultation record, increments a Micrometer counter and logs a line.
# Observed by that side effect, never by the model's prose, since qwen3's tool calling is
# nondeterministic. Includes the negative witness: with MCP_APA_ORDERS_URL pointed at a path no
# server answers at, the agent can never reach the remote tool, so no record or counter follows.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
TOTAL=0
PASSED=0

cleanup() {
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

consume_order() { # consume_order <orderId>
    curl -sf -X POST -H 'Content-Type: text/plain' --data "$1" "$APP/consume" >/dev/null
}

consultation_status() { # consultation_status <orderId>
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT order_status FROM orders.agent_order_status_consultation WHERE order_id='$1'"
}
consultation_recorded() { # the live model's own wording may vary; only presence is asserted, never its prose
    [[ -n "$(consultation_status "$1")" ]]
}
no_consultation() { [[ -z "$(consultation_status "$1")" ]]; }

SVC=langchain4j-programmatic-consumer
tempo_has_trace() {
    curl -sf -G "http://localhost:3200/api/search" --data-urlencode "tags=service.name=$SVC" | grep '"traceID"' >/dev/null
}
prom_has_metric() {
    curl -sf -G "http://localhost:19090/api/v1/query" --data-urlencode "query=target_info{service_name=\"$SVC\"}" \
        | grep '"result":\[{' >/dev/null
}
prom_has_counter() {
    curl -sf "http://localhost:19090/api/v1/label/__name__/values" | grep -i 'consumer_mcp_tool_invocations' >/dev/null
}
loki_has_log() { # loki_has_log <search string>
    local now; now=$(date +%s)
    curl -sf -G "http://localhost:3100/loki/api/v1/query_range" --data-urlencode "query={service_name=\"$SVC\"} |= \"$1\"" \
        --data-urlencode "start=$((now - 600))000000000" --data-urlencode "end=$((now + 60))000000000" \
        | grep '"values":\[\[' >/dev/null
}

restart_app() { "${COMPOSE[@]}" up -d --no-deps --force-recreate --wait app >/dev/null; }

# The model that drives the live tool-call round trip must already be pulled into the ollama
# volume, or the compose stack comes up with an assistant that cannot answer. This script
# never pulls.
../../scripts/ollama-model.sh check

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm \
         apa-24-package-api-as-mcp-tools apa-langchain4j-programmatic-consumer; do
    check "$c is healthy" healthy "$c"
done

consume_order O-9001
check "consuming O-9001 records a status (observed in PostgreSQL, not model prose)" \
    poll 120 consultation_recorded O-9001
check "Tempo has a trace for service $SVC" poll 90 tempo_has_trace
check "Prometheus has a metric from $SVC" poll 90 prom_has_metric
check "Prometheus has the recordConsultation tool-call counter" poll 90 prom_has_counter
check "Loki has the tool's own log line (observed, not model prose)" poll 90 loki_has_log "tool invoked: orderStatus"

# Negative witness: point the consumer's @McpToolBox("apa-orders") client at a path no server
# answers at, recreating the app container (which also resets its in-process Micrometer counter to
# zero), then ask it about an order it has never consulted before. The durable record is the
# witness, not a point-in-time Prometheus query: OrderStatusRecorder.recordConsultation increments
# the counter and writes the row in the same method call, so an absent row is already conclusive
# proof the counter never incremented either, and Prometheus keeps the previous container
# instance's last-exported (nonzero) sample live in its TSDB well past the restart, which would
# make an absence check on the metric name alone pass vacuously.
MCP_APA_ORDERS_URL=http://package-api-as-mcp-tools:8080/wrong-mcp-path restart_app
consume_order O-9002
sleep 8
check "negative witness: a broken @McpToolBox wiring leaves the remote tool unreachable (no consultation row for O-9002, so the counter in OrderStatusRecorder.recordConsultation was never incremented either)" \
    no_consultation O-9002

# Restore: the correct URL is the compose default, so recreating without an override reverts it.
restart_app
check "restored: the consumer with correct @McpToolBox wiring now records O-9002's consultation" \
    poll 120 consultation_recorded O-9002

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
