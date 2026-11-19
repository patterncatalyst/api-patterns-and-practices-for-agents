#!/usr/bin/env bash
# Starts the stack with the long-running-agent-workflow service and checks the order-fulfillment
# saga (Camel Saga EIP, in-memory CamelSagaService, no external LRA coordinator): a clean run
# reserves stock, takes payment and has the agent step book a carrier, consistently; a run whose
# payment step is declined compensates the earlier reservation, leaving no partial effect (the
# stock nets back to where it started). Includes the negative witness: with
# FULFILLMENT_COMPENSATION_ENABLED=false (COMPENSATE=off), a declined payment leaves the reserved
# stock reserved, a partial effect that survives the failure. KEEP=1 leaves the stack running
# afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
SVC=long-running-agent-workflow
APP=http://localhost:8080
SKU="SKU-20-A"
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

psql_() { docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc "$1"; }
stock() { psql_ "SELECT quantity_on_hand FROM inventory.inventory_item WHERE sku='$SKU'"; }
stock_is() { [[ "$(stock)" == "$1" ]]; } # stock_is <quantity>
fulfillment_status() { psql_ "SELECT status FROM orders.order_fulfillment WHERE order_id='$1'"; } # fulfillment_status <orderId>
status_is() { [[ "$(fulfillment_status "$1")" == "$2" ]]; } # status_is <orderId> <status>
payment_status() { local s; s="$(psql_ "SELECT status FROM payments.payment WHERE order_id='$1'")"; echo "${s:-none}"; }
payment_is() { [[ "$(payment_status "$1")" == "$2" ]]; } # payment_is <orderId> <status|none>
shipment_carrier() { psql_ "SELECT carrier FROM shipping.shipment WHERE order_id='$1'"; }
shipment_is_a_valid_carrier() { # the live model decides which carrier; only the shape is asserted, never its prose
    local c; c=$(shipment_carrier "$1")
    [[ "$c" == "FASTLINE" || "$c" == "AIRCO" ]]
}
no_shipment() { [[ -z "$(shipment_carrier "$1")" ]]; }

place() { # place <orderId> <sku> <quantity> <amount>: fire and forget, the DB is the witness
    curl -s -o /dev/null -X POST -H 'Content-Type: application/json' "$APP/orders" \
        --data "{\"id\":\"$1\",\"customerId\":\"CU-20\",\"itemSku\":\"$2\",\"quantity\":$3,\"amount\":$4}" || true
}

tempo_has_trace() {
    curl -sf -G "http://localhost:3200/api/search" --data-urlencode "tags=service.name=$SVC" | grep '"traceID"' >/dev/null
}
prom_has_metric() {
    curl -sf -G "http://localhost:19090/api/v1/query" --data-urlencode "query=target_info{service_name=\"$SVC\"}" \
        | grep '"result":\[{' >/dev/null
}
prom_has_counter() {
    curl -sf "http://localhost:19090/api/v1/label/__name__/values" | grep -i 'carrier_bookings' >/dev/null
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

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-long-running-agent-workflow; do
    check "$c is healthy" healthy "$c"
done

echo "A clean run completes every step, including the agent's carrier choice"
poll 30 stock >/dev/null
BEFORE_HAPPY="$(stock)"
place O-20101 "$SKU" 1 49.99
check "O-20101 completes (reserve, pay, agent books a carrier)" poll 60 status_is O-20101 COMPLETED
check "O-20101 took one unit of $SKU and kept it (no release on the happy path)" \
    stock_is "$((BEFORE_HAPPY - 1))"
check "O-20101's payment is COMPLETED" payment_is O-20101 COMPLETED
check "O-20101's shipment was booked with a valid carrier (FASTLINE or AIRCO), not asserted by prose" \
    shipment_is_a_valid_carrier O-20101

echo "A declined payment compensates the reservation and leaves no partial effect"
BEFORE_DECLINE="$(stock)"
place O-20102 "$SKU" 2 900.00
check "O-20102's payment is declined and the saga compensates" poll 60 status_is O-20102 COMPENSATED
check "O-20102's stock is back where it was before the reservation (no net change)" \
    stock_is "$BEFORE_DECLINE"
check "O-20102 has no payment row (the declined payment wrote nothing)" payment_is O-20102 none
check "O-20102 has no shipment row (the carrier step never ran)" no_shipment O-20102

echo "Observability"
check "Tempo has a trace for service $SVC" poll 90 tempo_has_trace
check "Prometheus has a metric from $SVC" poll 90 prom_has_metric
check "Prometheus has the bookCarrier tool-call counter" poll 90 prom_has_counter
check "Loki has the tool's own log line (observed, not model prose)" poll 90 loki_has_log "tool invoked: bookCarrier"

echo "Negative witness: FULFILLMENT_COMPENSATION_ENABLED=false (COMPENSATE=off)"
COMPENSATE=false restart_app
BEFORE_BITE="$(stock)"
place O-20103 "$SKU" 3 900.00
check "O-20103's payment is still declined" poll 60 status_is O-20103 STOCK_RESERVED
check "negative witness: with compensation disabled, O-20103's reserved stock stays reserved (a partial effect)" \
    stock_is "$((BEFORE_BITE - 3))"

echo "Restore: FULFILLMENT_COMPENSATION_ENABLED=true"
COMPENSATE=true restart_app
BEFORE_RESTORE="$(stock)"
place O-20104 "$SKU" 1 900.00
check "restored: O-20104's declined payment is compensated again" poll 60 status_is O-20104 COMPENSATED
check "restored: O-20104's stock is back where it was before the reservation" \
    stock_is "$BEFORE_RESTORE"

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
