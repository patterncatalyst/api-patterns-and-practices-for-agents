#!/usr/bin/env bash
# Starts the stack with the consuming-the-async-side service and checks that a ShipmentDelivered
# event on apa.shipping.delivered wakes the notification agent instead of the service polling for
# it: the woken agent composes a customer-facing message and NotificationRecorder writes a durable
# notify.notifications row keyed on the event's own order id, customer id and event type, never on
# the model's prose, since qwen3's composed message is nondeterministic. Includes three witnesses:
# the consumer-disabled negative witness (a delivered event sits unconsumed and no row is written),
# the idempotency witness (the same event redelivered yields exactly one row), and the restored
# path (the disabled consumer resumes and records what it missed). KEEP=1 leaves the stack running
# afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
SVC=consuming-the-async-side
TOPIC=apa.shipping.delivered
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

produce_delivered() { # produce_delivered <orderId> <customerId> <carrier> <trackingNumber>
    printf '%s:{"orderId":"%s","customerId":"%s","carrier":"%s","trackingNumber":"%s"}\n' "$1" "$1" "$2" "$3" "$4" \
        | docker exec -i apa-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
            --topic "$TOPIC" --property parse.key=true --property key.separator=: >/dev/null 2>&1
}

notification_customer() { # notification_customer <orderId>
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT customer_id FROM notify.notifications WHERE order_id='$1' AND event_type='SHIPMENT_DELIVERED'"
}
notification_recorded() { [[ -n "$(notification_customer "$1")" ]]; }
no_notification() { [[ -z "$(notification_customer "$1")" ]]; }
notification_row_count_is_one() { # notification_row_count_is_one <orderId>
    local count; count=$(docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT count(*) FROM notify.notifications WHERE order_id='$1'")
    [[ "$count" == "1" ]]
}

tempo_has_trace() {
    curl -sf -G "http://localhost:3200/api/search" --data-urlencode "tags=service.name=$SVC" | grep '"traceID"' >/dev/null
}
prom_has_metric() {
    curl -sf -G "http://localhost:19090/api/v1/query" --data-urlencode "query=target_info{service_name=\"$SVC\"}" \
        | grep '"result":\[{' >/dev/null
}
prom_has_counter() {
    curl -sf "http://localhost:19090/api/v1/label/__name__/values" | grep -i 'consumingasyncside' >/dev/null
}
loki_has_log() { # loki_has_log <search string>
    local now; now=$(date +%s)
    curl -sf -G "http://localhost:3100/loki/api/v1/query_range" --data-urlencode "query={service_name=\"$SVC\"} |= \"$1\"" \
        --data-urlencode "start=$((now - 600))000000000" --data-urlencode "end=$((now + 60))000000000" \
        | grep '"values":\[\[' >/dev/null
}

restart_app() { "${COMPOSE[@]}" up -d --no-deps --force-recreate --wait app >/dev/null; }

# The model that drives the live message-composition round trip must already be pulled into the
# ollama volume, or the compose stack comes up with an assistant that cannot answer. This script
# never pulls.
../../scripts/ollama-model.sh check

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-consuming-the-async-side; do
    check "$c is healthy" healthy "$c"
done

produce_delivered O-7501 CUST-7501 AIRCO AIR-500001
check "the woken agent records a notification for O-7501 (observed in PostgreSQL, not model prose)" \
    poll 120 notification_recorded O-7501
check "Tempo has a trace for service $SVC" poll 90 tempo_has_trace
check "Prometheus has a metric from $SVC" poll 90 prom_has_metric
check "Prometheus has the notification-recorded counter" poll 90 prom_has_counter
check "Loki has the recorder's own log line (observed, not model prose)" poll 90 loki_has_log "notification recorded"

# Idempotency witness: the same event, same order id and so the same (order_id, event_type) key,
# redelivered. NotificationRecorder's ON CONFLICT (order_id, event_type) DO NOTHING means the second
# delivery can add no second row, whichever delivery the composed message in the first row came
# from.
produce_delivered O-7502 CUST-7502 FASTLINE FL-500002
check "the first delivery of O-7502 is recorded" poll 120 notification_recorded O-7502
produce_delivered O-7502 CUST-7502 FASTLINE FL-500002
sleep 8
check "idempotency witness: redelivering O-7502's event stays at exactly one notification row" \
    notification_row_count_is_one O-7502

# Negative witness: disable the trigger consumer, recreating the app container (which also resets
# its in-process Micrometer counter to zero), then publish an event it never sees. The durable
# record is the witness, not a point-in-time Prometheus query: NotificationRecorder increments the
# counter and writes the row in the same method call, so an absent row is already conclusive proof
# the counter never incremented either, and Prometheus keeps the previous container instance's
# last-exported (nonzero) sample live in its TSDB well past the restart, which would make an
# absence check on the metric name alone pass vacuously.
CONSUMER_ASYNC_ENABLED=false restart_app
produce_delivered O-7503 CUST-7503 AIRCO AIR-500003
sleep 8
check "negative witness: a disabled consumer leaves the event unconsumed (no notification row for O-7503, so the counter in NotificationRecorder.record was never incremented either)" \
    no_notification O-7503

# Restore: the consumer resumes with its committed offset unchanged (it never advanced while
# disabled), so it reads O-7503's event, which auto-offset-reset=earliest never required anyway
# since the group had already committed past the earlier orders before being stopped.
CONSUMER_ASYNC_ENABLED=true restart_app
check "restored: the consumer that was disabled now records O-7503's notification" \
    poll 120 notification_recorded O-7503

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
