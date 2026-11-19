#!/usr/bin/env bash
# Starts the stack with the propose-preview-commit app and checks the dryRun preview, the real
# commit, and the REJECTED_BY_USER no-op on the same POST /orders/{id}/cancel endpoint.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
TOTAL=0
PASSED=0
TMP_FILES=()

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

cancel_call() { # cancel_call <order-id> <json-body> <outfile>: prints the HTTP status code
    curl -s -o "$3" -w '%{http_code}' -H 'Content-Type: application/json' \
        -d "$2" "$APP/orders/$1/cancel"
}

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

topic_count_for_key() { # topic_count_for_key <key>: number of records on apa.orders.cancelled keyed by <key>
    docker exec apa-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
        --topic apa.orders.cancelled --from-beginning --timeout-ms 15000 --property print.key=true 2>/dev/null \
        | grep -c "^$1"
}
topic_count_for_key_is() { [[ "$(topic_count_for_key "$1")" == "$2" ]]; }

DRY_RUN_FILE=""
COMMIT_FILE=""
REJECTED_FILE=""

dry_run_call_succeeds() {
    DRY_RUN_FILE=$(new_tmp_file)
    local status
    status=$(cancel_call O-5001 '{"reason":"checking the refund amount first","dryRun":true}' "$DRY_RUN_FILE")
    [[ "$status" == "200" ]]
}

dry_run_reports_the_review_shape() {
    jq -e '.orderId == "O-5001"
        and .beforeStatus == "PLACED"
        and .afterStatus == "CANCELLED"
        and .refundAmount == 149.00
        and (.wouldEmitEvents == ["OrderCancelled"])
        and .reversible == false' "$DRY_RUN_FILE" >/dev/null
}

commit_call_succeeds() { # commit_call_succeeds <order-id>
    COMMIT_FILE=$(new_tmp_file)
    local status
    status=$(cancel_call "$1" '{"reason":"customer changed mind"}' "$COMMIT_FILE")
    [[ "$status" == "200" ]]
}

rejected_by_user_is_graceful_no_op() {
    REJECTED_FILE=$(new_tmp_file)
    local status
    status=$(cancel_call O-5002 '{"decision":"REJECTED_BY_USER"}' "$REJECTED_FILE")
    [[ "$status" == "200" ]] || return 1
    jq -e '.orderId == "O-5002" and .decision == "REJECTED_BY_USER" and .message == "no action taken"' \
        "$REJECTED_FILE" >/dev/null
}

cancel_status() { # cancel_status <order-id> <json-body>: HTTP status only
    local f status
    f=$(new_tmp_file)
    status=$(cancel_call "$1" "$2" "$f")
    echo "$status"
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-propose-preview-commit; do
    check "$c is healthy" healthy "$c"
done

check "the seeded order O-5001 starts PLACED" order_status_is O-5001 PLACED
check "the seeded payment for O-5001 starts COMPLETED" payment_status_is O-5001 COMPLETED

check "a dryRun=true call on O-5001 succeeds with 200" dry_run_call_succeeds
check "the dry-run response reports the full review shape (before/after/refund/wouldEmit/reversible)" \
    dry_run_reports_the_review_shape
check "the dry run left the order row unchanged (still PLACED)" order_status_is O-5001 PLACED
check "the dry run left the payment row unchanged (still COMPLETED)" payment_status_is O-5001 COMPLETED
check "the dry run published zero events for O-5001" topic_count_for_key_is O-5001 0

check "the real commit for O-5001 (dryRun absent) succeeds with 200" commit_call_succeeds O-5001
check "the order flips to CANCELLED" order_status_is O-5001 CANCELLED
check "the payment flips to REFUNDED" payment_status_is O-5001 REFUNDED
check "exactly one OrderCancelled record keyed by O-5001 (the commit, not the earlier dry run)" \
    topic_count_for_key_is O-5001 1

check "decision=REJECTED_BY_USER on O-5002 is a graceful 200 no-op" rejected_by_user_is_graceful_no_op
check "the REJECTED_BY_USER decision left the order row unchanged (still PLACED)" order_status_is O-5002 PLACED
check "the REJECTED_BY_USER decision published zero events for O-5002" topic_count_for_key_is O-5002 0
check "O-5002 is still cancellable afterwards: a real commit now succeeds" commit_call_succeeds O-5002
check "exactly one OrderCancelled record keyed by O-5002 (the commit, not the rejected decision)" \
    topic_count_for_key_is O-5002 1

check "a plain commit (no dryRun, no decision) on O-5003 succeeds with 200" commit_call_succeeds O-5003
check "exactly one OrderCancelled record keyed by O-5003" topic_count_for_key_is O-5003 1

check "negative witness: a dry run on an already-cancelled order (O-5004) is rejected with 409" \
    bash -c '[[ "$(curl -s -o /dev/null -w "%{http_code}" -H "Content-Type: application/json" -d "{\"dryRun\":true}" '"$APP"'/orders/O-5004/cancel)" == "409" ]]'
check "negative witness: committing an already-cancelled order (O-5004) is rejected with 409" \
    bash -c '[[ "$(curl -s -o /dev/null -w "%{http_code}" -H "Content-Type: application/json" -d "{}" '"$APP"'/orders/O-5004/cancel)" == "409" ]]'
check "negative witness: cancelling an unknown order is rejected with 404" \
    bash -c '[[ "$(curl -s -o /dev/null -w "%{http_code}" -H "Content-Type: application/json" -d "{}" '"$APP"'/orders/O-DOES-NOT-EXIST/cancel)" == "404" ]]'

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
