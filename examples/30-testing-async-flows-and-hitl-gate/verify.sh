#!/usr/bin/env bash
# Tests an async flow and its human-in-the-loop gate the way any other contract is tested: by
# OBSERVING THE EFFECT, not an exit code. The subject is chapter 19's cancel-and-refund gate,
# reused unchanged (compose builds it from 19-human-in-the-loop-gate/Containerfile). An async,
# human-gated action has no synchronous return value worth asserting: the trigger answers 202
# and holds, and the real outcome only ever shows up later, as a durable row and a Kafka record.
# So every assertion here reads a DURABLE EFFECT, and reads it under a bounded poll:
#   - gate, order and payment state from PostgreSQL (psql in the apa-postgres container)
#   - topic offsets on apa.orders.pending-decisions and apa.orders.decisions
#     (kafka-console-consumer/producer in the apa-kafka container)
# A test that asserted on the trigger's exit code would pass whether or not the gate ever held,
# approved, or stayed put: the methodology, not the HTTP status, is the lesson.
#
# Checks:
#   (1) Pause with no side effect: the trigger opens a PENDING gate and publishes exactly one
#       pending-decision record; the order stays PLACED and its payment COMPLETED, and nothing
#       lands on the decisions topic yet.
#   (2) Approve executes exactly once: an APPROVE decision resumes the gate to CANCELLED +
#       REFUNDED with exactly one execution-log row.
#   (3) Idempotency: the same APPROVE redelivered leaves the execution log at exactly one row.
#   (4) Reject is a terminal no-op: a REJECT on a different order terminates it with no side effect.
#   Observability: a Tempo trace, a Prometheus metric and a Loki log line for the service.
#   Negative witness (dropped event): a triggered order whose decision is NEVER published stays
#   PENDING with no side effect for a bounded window. A dropped decision is no effect, which is
#   exactly what effect-observation catches and an exit-code test cannot.
#
# The second half of the negative witness is a LIVE BITE the orchestrator runs separately (see
# README, "The negative witness"): rebuild ch19 with the gate removed (swap
# gateService.proposeAndHold(...) for an immediate cancel-and-refund) and confirm check (1)'s
# "no side effect before a decision" assertions FAIL, then revert. Check (1) is structured so
# that bite is observable: with the gate removed, the order is CANCELLED and the payment REFUNDED
# the instant the trigger returns, and no pending-decision record is ever published.
#
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
SVC=human-in-the-loop-gate
PENDING_TOPIC=apa.orders.pending-decisions
DECISIONS_TOPIC=apa.orders.decisions
DROP_WINDOW=20   # seconds a dropped decision is given to (wrongly) materialize an effect
TMP="$(mktemp -d)"
TOTAL=0
PASSED=0

cleanup() {
    rm -rf "$TMP"
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

precondition() { # precondition "<description>" <command...>: counted as a check; failing it stops the run
    local desc="$1"; shift
    TOTAL=$((TOTAL + 1))
    if "$@" >/dev/null 2>&1; then
        PASSED=$((PASSED + 1)); printf 'PASS %d: %s\n' "$TOTAL" "$desc"
    else
        printf 'PRECONDITION FAILED %d: %s\n' "$TOTAL" "$desc" >&2; exit 1
    fi
}

poll() { # poll <seconds> <command...>: retry every 2 s until the command succeeds, or fail at the deadline
    local end=$((SECONDS + $1)); shift
    until "$@"; do
        (( SECONDS < end )) || return 1
        sleep 2
    done
}

# holds_for <seconds> <command...>: the command must stay true for the whole window. Used for the
# dropped-event witness: a gate that never receives a decision must stay PENDING with no effect.
holds_for() {
    local end=$((SECONDS + $1)); shift
    until (( SECONDS >= end )); do
        "$@" || return 1
        sleep 2
    done
}

healthy() { [[ "$(docker inspect -f '{{.State.Health.Status}}' "$1")" == "healthy" ]]; }

# --- the agent-facing surface (ch19's trigger and gate reader)
trigger_cancel() { # trigger_cancel <orderId> <reason>: prints the HTTP status, body in $TMP/trigger.json
    curl -s -o "$TMP/trigger.json" -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
        -d "{\"reason\":\"$2\"}" "$APP/orders/$1/cancel-request"
}
trigger_status_is() { [[ "$(trigger_cancel "$1" "$2")" == "$3" ]]; } # trigger_status_is <orderId> <reason> <code>

gate_field() { curl -sf "$APP/orders/$1/cancel-gate" | jq -r ".$2"; } # gate_field <orderId> <field>
gate_status_is() { [[ "$(gate_field "$1" status)" == "$2" ]]; }       # gate_status_is <orderId> <status>

# --- durable effect in PostgreSQL
order_status() { # order_status <orderId>
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT status FROM orders.orders WHERE id='$1'"
}
order_status_is() { [[ "$(order_status "$1")" == "$2" ]]; }

payment_status() { # payment_status <orderId>
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT status FROM payments.payments WHERE order_id='$1'"
}
payment_status_is() { [[ "$(payment_status "$1")" == "$2" ]]; }

execution_count() { # execution_count <correlationId>
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT COUNT(*) FROM orders.cancel_execution_log WHERE correlation_id='$1'"
}
execution_count_is() { [[ "$(execution_count "$1")" == "$2" ]]; }

# order is PLACED and its payment COMPLETED: the "no side effect yet" shape, as one predicate
no_side_effect() { order_status_is "$1" PLACED && payment_status_is "$1" COMPLETED; }

# --- durable effect on Kafka topic offsets (apa-kafka container)
topic_count_for_key() { # topic_count_for_key <topic> <key>: number of records on <topic> keyed by <key>
    docker exec apa-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
        --topic "$1" --from-beginning --timeout-ms 15000 --property print.key=true 2>/dev/null \
        | grep -c "^$2"
}
topic_count_for_key_is() { [[ "$(topic_count_for_key "$1" "$2")" == "$3" ]]; }

publish_decision() { # publish_decision <correlationId> <decision>: same key/shape CancelDecisionRoute consumes
    printf '%s:{"correlationId":"%s","decision":"%s","decidedBy":"reviewer@example.com"}\n' "$1" "$1" "$2" \
        | docker exec -i apa-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
            --topic "$DECISIONS_TOPIC" --property parse.key=true --property key.separator=: >/dev/null 2>&1
}

# --- observability (reuses ch19's service.name)
tempo_has_trace() {
    curl -sf -G "http://localhost:3200/api/search" --data-urlencode "tags=service.name=$SVC" | grep '"traceID"' >/dev/null
}
prom_has_metric() {
    curl -sf -G "http://localhost:19090/api/v1/query" --data-urlencode "query=target_info{service_name=\"$SVC\"}" \
        | grep '"result":\[{' >/dev/null
}
loki_has_log() { # loki_has_log <search string>
    local now; now=$(date +%s)
    curl -sf -G "http://localhost:3100/loki/api/v1/query_range" --data-urlencode "query={service_name=\"$SVC\"} |= \"$1\"" \
        --data-urlencode "start=$((now - 600))000000000" --data-urlencode "end=$((now + 60))000000000" \
        | grep '"values":\[\[' >/dev/null
}

# The model that drives the live tool-call round trip must already be pulled into the ollama
# volume, or the stack comes up with an assistant that cannot answer. This script never pulls.
../../scripts/ollama-model.sh check

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted run),
# then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-30-human-in-the-loop-gate; do
    check "$c is healthy" healthy "$c"
done

echo "Seed check: effect-observation starts from a known baseline"
check "seeded order O-19001 starts PLACED" order_status_is O-19001 PLACED
check "seeded payment for O-19001 starts COMPLETED" payment_status_is O-19001 COMPLETED

echo "(1) Pause with no side effect: the gate holds, observed in rows and topic offsets"
precondition "POST /orders/O-19001/cancel-request is accepted (202)" \
    trigger_status_is O-19001 "customer requested cancellation" 202
CORR_1="$(gate_field O-19001 correlationId)"
check "the gate for O-19001 resumes to PENDING" poll 30 gate_status_is O-19001 PENDING
check "O-19001 is still PLACED: no cancel before a decision" order_status_is O-19001 PLACED
check "O-19001's payment is still COMPLETED: no refund before a decision" payment_status_is O-19001 COMPLETED
check "exactly one pending-decision record is keyed by the correlation id" \
    poll 30 topic_count_for_key_is "$PENDING_TOPIC" "$CORR_1" 1
check "nothing has landed on the decisions topic yet for the correlation id" \
    topic_count_for_key_is "$DECISIONS_TOPIC" "$CORR_1" 0

echo "(2) Approve executes exactly once: the effect is the CANCELLED/REFUNDED pair and one log row"
publish_decision "$CORR_1" APPROVE
check "the gate for O-19001 resumes to APPROVED_EXECUTED" poll 30 gate_status_is O-19001 APPROVED_EXECUTED
check "O-19001 is now CANCELLED" poll 30 order_status_is O-19001 CANCELLED
check "O-19001's payment is now REFUNDED" poll 30 payment_status_is O-19001 REFUNDED
check "exactly one execution is logged for the correlation id" poll 30 execution_count_is "$CORR_1" 1

echo "(3) Idempotency: redelivering the same APPROVE leaves the execution log at one row"
publish_decision "$CORR_1" APPROVE
sleep 5
check "O-19001 is still CANCELLED (unchanged by the redelivered APPROVE)" order_status_is O-19001 CANCELLED
check "O-19001's payment is still REFUNDED (unchanged by the redelivered APPROVE)" payment_status_is O-19001 REFUNDED
check "the execution log still shows exactly one execution for the correlation id" execution_count_is "$CORR_1" 1

echo "(4) Reject is a terminal no-op: a different order terminates with no side effect"
precondition "POST /orders/O-19002/cancel-request is accepted (202)" \
    trigger_status_is O-19002 "customer requested cancellation" 202
CORR_2="$(gate_field O-19002 correlationId)"
check "the gate for O-19002 resumes to PENDING" poll 30 gate_status_is O-19002 PENDING
publish_decision "$CORR_2" REJECT
check "the gate for O-19002 resumes to REJECTED_BY_USER" poll 30 gate_status_is O-19002 REJECTED_BY_USER
check "O-19002 is still PLACED: REJECT performs no cancel" order_status_is O-19002 PLACED
check "O-19002's payment is still COMPLETED: REJECT performs no refund" payment_status_is O-19002 COMPLETED
check "no execution is ever logged for the rejected correlation id" execution_count_is "$CORR_2" 0

echo "Observability: the gate's own signals reach LGTM"
check "Tempo has a trace for service $SVC" poll 90 tempo_has_trace
check "Prometheus has a metric from $SVC" poll 90 prom_has_metric
check "Loki has the tool's own log line (observed, not model prose)" poll 90 loki_has_log "tool invoked: proposeCancelAndRefund"

echo "Negative witness (dropped event): a decision that never arrives is no effect"
# Trigger a fresh order, then NEVER publish its decision. A dropped decision must leave the gate
# PENDING and the order untouched; effect-observation catches this exactly because there is no
# effect to observe. An exit-code test could not: the trigger returned 202 here just as it did
# for the approved and rejected orders.
precondition "POST /orders/O-19006/cancel-request is accepted (202)" \
    trigger_status_is O-19006 "customer requested cancellation" 202
CORR_3="$(gate_field O-19006 correlationId)"
precondition "the gate for O-19006 is PENDING" poll 30 gate_status_is O-19006 PENDING
check "dropped decision: O-19006 stays PENDING with no side effect for ${DROP_WINDOW}s" \
    holds_for "$DROP_WINDOW" gate_status_is O-19006 PENDING
check "dropped decision: O-19006 stays PLACED and COMPLETED (no cancel, no refund)" no_side_effect O-19006
check "dropped decision: nothing is ever logged as executed for the correlation id" execution_count_is "$CORR_3" 0

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
