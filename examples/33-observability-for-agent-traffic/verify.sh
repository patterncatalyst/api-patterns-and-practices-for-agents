#!/usr/bin/env bash
# Starts the stack with the observability-for-agent-traffic service and checks that one real
# agent call leaves a trail you can cost and trace: a GenAI span in Tempo, a nonzero token-usage
# metric in Prometheus, a durable token_cost row in Postgres, and the tool's own log line in
# Loki. Includes the negative witness: with the recorder disabled, the same call still answers
# and still emits its GenAI span and metric, but writes no new durable row.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
SVC=observability-for-agent-traffic
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

ask_order() { # ask_order <orderId>: the trigger, a plain HTTP GET, no Camel route anywhere
    curl -sf "http://localhost:8080/agent/orders/$1/status" >/dev/null
}

token_cost_row_count() {
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT count(*) FROM agenttraffic.token_cost" | tr -d '[:space:]'
}
token_cost_row_for_real_model() {
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT count(*) FROM agenttraffic.token_cost WHERE model = 'qwen3:4b-instruct-2507-q4_K_M'" \
        | tr -d '[:space:]'
}
a_real_model_row_exists() { [[ "$(token_cost_row_for_real_model)" -gt 0 ]]; }
row_count_grew_beyond() { [[ "$(token_cost_row_count)" -gt "$1" ]]; } # row_count_grew_beyond <baseline>

# The AiService-level span quarkus-langchain4j names "<Interface>.<method>" and the tool-call
# span it names "langchain4j.tools.<tool>" (confirmed against the resolved jar; the first is NOT
# prefixed "langchain4j.aiservices.", a naming assumption this committed check deliberately does
# not repeat). Both are searched by TraceQL against Tempo's HTTP API.
tempo_has_span() { # tempo_has_span <TraceQL span name>
    curl -sf -G "http://localhost:3200/api/search" --data-urlencode "q={resource.service.name=\"$SVC\" && name=\"$1\"}" \
        | grep '"traceID"' >/dev/null
}
prom_has_token_usage_metric() { # the fresh stack runs only this one service, so no label filter is needed
    curl -sf -G "http://localhost:19090/api/v1/query" \
        --data-urlencode 'query=sum(gen_ai_client_token_usage_total) > 0' \
        | grep '"result":\[{' >/dev/null
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

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-observability-for-agent-traffic; do
    check "$c is healthy" healthy "$c"
done

check "the agent answers a real order-status question (one real model call)" ask_order O-3301
check "the real model call leaves a token_cost row for the pinned model (observed in PostgreSQL, not model prose)" \
    poll 120 a_real_model_row_exists
check "Tempo has the AiService-level span OrderStatusAdvisor.answer for $SVC" \
    poll 90 tempo_has_span "OrderStatusAdvisor.answer"
check "Tempo has the tool-call span langchain4j.tools.orderStatus for $SVC" \
    poll 90 tempo_has_span "langchain4j.tools.orderStatus"
check "Prometheus has a nonzero gen_ai_client_token_usage_total for $SVC" \
    poll 90 prom_has_token_usage_metric
check "Loki has the tool's own log line (observed, not model prose)" poll 90 loki_has_log "tool invoked: orderStatus"
check "Loki has the recorder's own log line" poll 90 loki_has_log "token cost recorded"

# Negative witness: disable the recorder, recreating the app container (COST_RECORDER_ENABLED is
# read once at startup), then make a fresh call. The GenAI span and the token-usage metric are
# unaffected (Prometheus keeps a previous container instance's last-exported, nonzero sample
# live in its TSDB well past a restart, which is exactly why this bite is the durable row's
# absence, not a metric-name check): the row count before and after the disabled call must be
# identical, proving TokenCostRecorder.recordCost returned before ever opening a connection.
before=$(token_cost_row_count)
COST_RECORDER_ENABLED=false restart_app
check "the agent still answers with the recorder disabled" ask_order O-3302
sleep 8
after=$(token_cost_row_count)
check "negative witness: a disabled recorder writes no new token_cost row ($before before, $after after)" \
    [ "$before" = "$after" ]

# Restore: a further call with the recorder back on must grow the row count again.
COST_RECORDER_ENABLED=true restart_app
check "the agent still answers now that the recorder is restored" ask_order O-3303
check "restored: the recorder writes a new token_cost row again" poll 120 row_count_grew_beyond "$after"

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
