#!/usr/bin/env bash
# Starts the stack with the event-triggered-agent-wakeup service and checks that a
# payment-processed event wakes the agent instead of the service polling for it: the woken
# agent's one prepShipment tool call writes a durable shipment_prep record and is observable by
# its own counter, log line and span, never by the model's prose, since qwen3's tool calling is
# nondeterministic. Includes the negative witness: with the trigger consumer disabled, the same
# event sits unconsumed and no record is written (so the counter, incremented in the same method
# call, never incremented either), until the consumer is restored and reads what it missed.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
SVC=event-triggered-agent-wakeup
TOPIC=apa.payments.processed
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

produce_payment() { # produce_payment <paymentId> <orderId> <amount>
    printf '%s:{"id":"%s","orderId":"%s","amount":%s,"status":"COMPLETED"}\n' "$2" "$1" "$2" "$3" \
        | docker exec -i apa-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
            --topic "$TOPIC" --property parse.key=true --property key.separator=: >/dev/null 2>&1
}

shipment_prep_carrier() { # shipment_prep_carrier <orderId>
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT carrier FROM shipping.shipment_prep WHERE order_id='$1'"
}
shipment_prep_is() { [[ "$(shipment_prep_carrier "$1")" == "$2" ]]; }
shipment_prep_is_a_valid_carrier() { # the live model decides which carrier; only the shape is asserted, never its prose
    local c; c=$(shipment_prep_carrier "$1")
    [[ "$c" == "FASTLINE" || "$c" == "AIRCO" ]]
}
no_shipment_prep() { [[ -z "$(shipment_prep_carrier "$1")" ]]; }

tempo_has_trace() {
    curl -sf -G "http://localhost:3200/api/search" --data-urlencode "tags=service.name=$SVC" | grep '"traceID"' >/dev/null
}
prom_has_metric() {
    curl -sf -G "http://localhost:19090/api/v1/query" --data-urlencode "query=target_info{service_name=\"$SVC\"}" \
        | grep '"result":\[{' >/dev/null
}
prom_has_counter() {
    curl -sf "http://localhost:19090/api/v1/label/__name__/values" | grep -i 'agentwakeup' >/dev/null
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

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-event-triggered-agent-wakeup; do
    check "$c is healthy" healthy "$c"
done

produce_payment PAY-7001 O-7001 49.99
check "the woken agent records a shipment prep for O-7001 (observed in PostgreSQL, not model prose)" \
    poll 120 shipment_prep_is_a_valid_carrier O-7001
check "Tempo has a trace for service $SVC" poll 90 tempo_has_trace
check "Prometheus has a metric from $SVC" poll 90 prom_has_metric
check "Prometheus has the prepShipment tool-call counter" poll 90 prom_has_counter
check "Loki has the tool's own log line (observed, not model prose)" poll 90 loki_has_log "tool invoked: prepShipment"

# Negative witness: disable the trigger consumer, recreating the app container (which also
# resets its in-process Micrometer counter to zero), then publish an event it never sees. The
# durable record is the witness, not a point-in-time Prometheus query: ShipmentPrepTool
# increments the counter and writes the row in the same method call, so an absent row is already
# conclusive proof the counter never incremented either, and Prometheus keeps the previous
# container instance's last-exported (nonzero) sample live in its TSDB well past the restart,
# which would make an absence check on the metric name alone pass vacuously.
WAKEUP_CONSUMER_ENABLED=false restart_app
produce_payment PAY-7002 O-7002 199.99
sleep 8
check "negative witness: a disabled consumer leaves the event unconsumed (no shipment_prep row for O-7002, so the counter in ShipmentPrepTool.prepShipment was never incremented either)" \
    no_shipment_prep O-7002

# Restore: the consumer resumes with its committed offset unchanged (it never advanced while
# disabled), so it reads O-7002's event, which auto-offset-reset=earliest never required anyway
# since the group had already committed past O-7001 before being stopped.
WAKEUP_CONSUMER_ENABLED=true restart_app
check "restored: the consumer that was disabled now records O-7002's shipment prep" \
    poll 120 shipment_prep_is_a_valid_carrier O-7002

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
