#!/usr/bin/env bash
# Starts the stack with ch13's package-api-as-mcp-tools server and this chapter's consumer, and
# checks that POST /consume with an order id makes OrderStatusConsumerAgent (a @RegisterAiService
# method annotated @McpToolBox("apa-orders")) call the REMOTE orderStatus tool over Streamable HTTP
# MCP, then call its one local tool, OrderStatusRecorder, which reads the status and tracking number
# out of the remote tool's own JSON result and writes a durable
# consumer.agent_order_status_consultation row, increments a Micrometer counter and logs a line.
# Observed by that side effect, never by the model's prose, since qwen3's tool calling is
# nondeterministic. The recorded tracking number is ch13's seeded FL-778821, a value the model has
# no way to learn except by reaching the remote tool. Includes the negative witness: with
# MCP_APA_ORDERS_URL pointed at a path no server answers at, the agent can never reach the remote
# tool, so no row can carry that seeded tracking number. KEEP=1 leaves the stack running afterwards.
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

# ch13 seeds exactly one order, O-9001: status SHIPPED, shipment tracking FL-778821. The tracking
# number is the witness value: the model has no way to produce it except by actually reaching the
# remote orderStatus tool, so a row carrying it proves the round trip, and its absence proves the
# break. Neither depends on the model's prose.
SEEDED_ORDER=O-9001
SEEDED_STATUS=SHIPPED
SEEDED_TRACKING=FL-778821

consultation_column() { # consultation_column <column> <orderId>
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT $1 FROM consumer.agent_order_status_consultation WHERE order_id='$2'"
}
clear_consultations() {
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -c \
        "DELETE FROM consumer.agent_order_status_consultation" >/dev/null
}
recorded_from_remote() { # the row carries ch13's seeded status AND unguessable tracking number
    [[ "$(consultation_column order_status "$1")" == "$SEEDED_STATUS" \
        && "$(consultation_column tracking_number "$1")" == "$SEEDED_TRACKING" ]]
}
not_recorded_from_remote() { # no row for this order carries the seeded tracking number
    [[ "$(consultation_column tracking_number "$1")" != "$SEEDED_TRACKING" ]]
}

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
# For the negative witness: a broken @McpToolBox URL makes the MCP client's auto health check fail,
# so the app never reports ready and `--wait` would abort. Recreate without waiting on health; the
# process still serves /consume, the remote tool call just fails, so no record is written.
restart_app_nowait() { "${COMPOSE[@]}" up -d --no-deps --force-recreate app >/dev/null; }

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

consume_order "$SEEDED_ORDER"
check "consulting $SEEDED_ORDER records ch13's composed status and seeded tracking (observed in PostgreSQL, not model prose)" \
    poll 120 recorded_from_remote "$SEEDED_ORDER"
check "Tempo has a trace for service $SVC" poll 90 tempo_has_trace
check "Prometheus has a metric from $SVC" poll 90 prom_has_metric
check "Prometheus has the recordConsultation tool-call counter" poll 90 prom_has_counter
check "Loki has the tool's own log line (observed, not model prose)" poll 90 loki_has_log "tool invoked: orderStatus"

# Negative witness: clear the recorded row, point the consumer's @McpToolBox("apa-orders") client at
# a path no server answers at, recreate the app container, then ask about the same seeded order. The
# model can never reach the remote orderStatus tool, so it has no way to learn the seeded tracking
# number FL-778821: whether it writes no row at all, or a row from a fabricated or error result, that
# row cannot carry the seeded tracking number. Asserting on remote-derived content, not row presence,
# is deliberate, because OrderStatusConsumerAgent also has a LOCAL recordConsultation tool that can
# write a row even when the remote tool is unreachable; the tracking number is what the local tool
# alone can never supply.
clear_consultations
MCP_APA_ORDERS_URL=http://package-api-as-mcp-tools:8080/wrong-mcp-path restart_app_nowait
sleep 15
consume_order "$SEEDED_ORDER" || true
sleep 8
check "negative witness: a broken @McpToolBox wiring cannot produce ch13's seeded tracking (no FL-778821 for $SEEDED_ORDER)" \
    not_recorded_from_remote "$SEEDED_ORDER"

# Restore: the correct URL is the compose default, so recreating without an override reverts it.
restart_app
consume_order "$SEEDED_ORDER" || true
check "restored: the consumer with correct @McpToolBox wiring again records $SEEDED_ORDER with its seeded tracking" \
    poll 120 recorded_from_remote "$SEEDED_ORDER"

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
