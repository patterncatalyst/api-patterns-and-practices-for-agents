#!/usr/bin/env bash
# Starts the stack with the multi-agent-choreography service and checks that placing one order
# over HTTP runs the whole three-agent choreography with no orchestrator bean: the order agent
# records the order and publishes OrderPlaced; the shipping agent reacts on its own, schedules a
# shipment and publishes ShipmentScheduled; the notification agent reacts on its own and records
# the terminal notification. Each hop is observable by its own durable row, counter and log line,
# never by the model's prose, since qwen3's tool calling is nondeterministic.
#
# This script does not run the negative witness (a dropped ShipmentScheduled event stalling the
# chain before the notification agent); see the trailing comment below for that.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
SVC=multi-agent-choreography
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

place_order() { # place_order <orderId> <customerId> <amount>
    curl -sf -X POST -H 'Content-Type: application/json' \
        -d "{\"id\":\"$1\",\"customerId\":\"$2\",\"itemSku\":\"SKU-1\",\"quantity\":1,\"amount\":$3}" \
        http://localhost:8080/orders >/dev/null
}

psql_one() { # psql_one <sql>
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc "$1"
}

order_is_recorded() { [[ -n "$(psql_one "SELECT id FROM orders.orders WHERE id='$1'")" ]]; } # order_is_recorded <orderId>
shipment_carrier() { psql_one "SELECT carrier FROM shipping.shipments WHERE order_id='$1'"; } # shipment_carrier <orderId>
shipment_is_a_valid_carrier() { # the live model decides which carrier; only the shape is asserted, never its prose
    local c; c=$(shipment_carrier "$1")
    [[ "$c" == "FASTLINE" || "$c" == "AIRCO" ]]
}
no_shipment() { [[ -z "$(shipment_carrier "$1")" ]]; } # no_shipment <orderId>
notification_event_type() { psql_one "SELECT event_type FROM notifications.notifications WHERE order_id='$1'"; } # notification_event_type <orderId>
notification_is_recorded() { [[ -n "$(notification_event_type "$1")" ]]; } # notification_is_recorded <orderId>
no_notification() { [[ -z "$(notification_event_type "$1")" ]]; } # no_notification <orderId>

tempo_has_trace() {
    curl -sf -G "http://localhost:3200/api/search" --data-urlencode "tags=service.name=$SVC" | grep '"traceID"' >/dev/null
}
prom_has_metric() {
    curl -sf -G "http://localhost:19090/api/v1/query" --data-urlencode "query=target_info{service_name=\"$SVC\"}" \
        | grep '"result":\[{' >/dev/null
}
prom_has_counter() { # prom_has_counter <name fragment>
    curl -sf "http://localhost:19090/api/v1/label/__name__/values" | grep -i "$1" >/dev/null
}
loki_has_log() { # loki_has_log <search string>
    local now; now=$(date +%s)
    curl -sf -G "http://localhost:3100/loki/api/v1/query_range" --data-urlencode "query={service_name=\"$SVC\"} |= \"$1\"" \
        --data-urlencode "start=$((now - 600))000000000" --data-urlencode "end=$((now + 60))000000000" \
        | grep '"values":\[\[' >/dev/null
}

# The model that drives the live tool-call round trip at every one of the three hops must
# already be pulled into the ollama volume, or the compose stack comes up with agents that
# cannot answer. This script never pulls.
../../scripts/ollama-model.sh check

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-multi-agent-choreography; do
    check "$c is healthy" healthy "$c"
done

place_order O-7001 CUST-7001 199.99
check "the order agent recorded O-7001 (observed in PostgreSQL, not model prose)" poll 30 order_is_recorded O-7001
check "the shipping agent reacted to OrderPlaced and scheduled a shipment for O-7001" \
    poll 120 shipment_is_a_valid_carrier O-7001
check "the notification agent reacted to ShipmentScheduled and recorded a notification for O-7001" \
    poll 120 notification_is_recorded O-7001

check "Tempo has a trace for service $SVC" poll 90 tempo_has_trace
check "Prometheus has a metric from $SVC" poll 90 prom_has_metric
check "Prometheus has the order agent's placeOrder tool-call counter" poll 90 prom_has_counter choreography_order
check "Prometheus has the shipping agent's scheduleShipment tool-call counter" poll 90 prom_has_counter choreography_shipping
check "Prometheus has the notification agent's sendNotification tool-call counter" poll 90 prom_has_counter choreography_notification
check "Loki has the order agent's own log line (observed, not model prose)" poll 90 loki_has_log "tool invoked: placeOrder"
check "Loki has the shipping agent's own log line (observed, not model prose)" poll 90 loki_has_log "tool invoked: scheduleShipment"
check "Loki has the notification agent's own log line (observed, not model prose)" poll 90 loki_has_log "tool invoked: sendNotification"

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]

# NEGATIVE WITNESS (not run by this script; the orchestrator runs this live in the main loop):
#   1. CHOREOGRAPHY_DROP_SHIPMENT_SCHEDULED=true "${COMPOSE[@]}" up -d --no-deps --force-recreate --wait app
#      (recreates the app container, which also resets its in-process Micrometer counters to
#      zero; the shipping agent's Camel filter() in ShippingEventRoutes now silently drops every
#      ShipmentScheduled event before it reaches Kafka).
#   2. place_order O-7002 CUST-7002 199.99
#   3. poll ~30s: order_is_recorded O-7002 must become true (the order agent is untouched) and
#      shipment_is_a_valid_carrier O-7002 must become true (the shipping agent still schedules
#      and writes its own row; only the publish is dropped), but notification_is_recorded O-7002
#      must stay false for the whole window: the dropped event isolates the break at exactly the
#      shipping-to-notification hop, never at order-to-shipping.
#   4. CHOREOGRAPHY_DROP_SHIPMENT_SCHEDULED=false "${COMPOSE[@]}" up -d --no-deps --force-recreate --wait app
#      (restore); place_order O-7003 CUST-7003 199.99 and poll notification_is_recorded O-7003 to
#      prove the chain completes again once the filter reopens. O-7002 itself stays unnotified
#      forever: its ShipmentScheduled event was never published, so there is nothing left on
#      apa.shipping.scheduled for a restored consumer to read, unlike 17-event-triggered-agent-
#      wakeup's disabled-consumer witness, where the event survives on the topic and is read once
#      the consumer resumes.
#   Assert every step via the rows and counters above, never via model prose.
