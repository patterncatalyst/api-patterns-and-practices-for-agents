#!/usr/bin/env bash
# Starts the stack with the intent-endpoint app and checks the atomic cancel-and-refund saga.
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

healthy() { [[ "$(docker inspect -f '{{.State.Health.Status}}' "$1")" == "healthy" ]]; }

cancel() { # cancel <order-id> <reason>: HTTP status code of the cancel-and-refund call
    curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
        -d "{\"reason\":\"$2\"}" "$APP/orders/$1/cancel-and-refund"
}
cancel_is() { [[ "$(cancel "$1" "$2")" == "$3" ]]; }
cancel_2xx() { [[ "$(cancel "$1" "$2")" =~ ^2 ]]; }

order_status() {
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT status FROM orders.orders WHERE id='$1'"
}
order_status_is() { [[ "$(order_status "$1")" == "$2" ]]; }

payment_status() {
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT status FROM payments.payments WHERE order_id='$1'"
}
payment_status_is() { [[ "$(payment_status "$1")" == "$2" ]]; }

no_partial_state() { # no_partial_state <order-id>: order and payment moved together, or not at all
    local joined
    joined=$(docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT o.status || '|' || p.status FROM orders.orders o JOIN payments.payments p ON p.order_id = o.id WHERE o.id = '$1'")
    [[ "$joined" == "CANCELLED|REFUNDED" ]]
}

topic_count_for_key() { # topic_count_for_key <key>: number of records on apa.orders.cancelled keyed by <key>
    docker exec apa-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
        --topic apa.orders.cancelled --from-beginning --timeout-ms 15000 --property print.key=true 2>/dev/null \
        | grep -c "^$1"
}
topic_count_for_key_is() { [[ "$(topic_count_for_key "$1")" == "$2" ]]; }

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-intent-endpoint; do
    check "$c is healthy" healthy "$c"
done

check "the seeded order O-5001 starts PLACED" order_status_is O-5001 PLACED
check "the seeded payment for O-5001 starts COMPLETED" payment_status_is O-5001 COMPLETED

check "one POST to cancel-and-refund succeeds" cancel_2xx O-5001 "customer changed mind"
check "the order flips to CANCELLED" order_status_is O-5001 CANCELLED
check "the payment flips to REFUNDED" payment_status_is O-5001 REFUNDED
check "no partial state is observable: order and payment moved together" no_partial_state O-5001
check "exactly one OrderCancelled record keyed by O-5001 on apa.orders.cancelled" \
    topic_count_for_key_is O-5001 1

check "negative witness: cancelling an already-cancelled order is rejected, not re-run" \
    cancel_is O-5001 "second attempt" 409
check "negative witness: cancelling an unknown order is rejected" \
    cancel_is O-DOES-NOT-EXIST "n/a" 404

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
