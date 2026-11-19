#!/usr/bin/env bash
# Starts the stack with the human-in-the-loop-gate service and checks that a high-value
# cancel-and-refund is never executed directly: the trigger (POST .../cancel-request) makes a
# direct quarkus-langchain4j-ollama agent PROPOSE the cancel-and-refund by calling its one tool,
# which holds the side effect (a PENDING row, plus a pending-decision event on
# apa.orders.pending-decisions) instead of acting. A decision event on apa.orders.decisions,
# carrying the same correlation id, resumes it: APPROVE performs the cancel-and-refund exactly
# once, even when the same APPROVE is redelivered; REJECT terminates with REJECTED_BY_USER and
# performs no side effect, ever. Also checks the committed decision-event schema registers in
# Apicurio under BACKWARD. The negative witness (gate removed, executing the proposed action
# immediately instead of holding it) is demonstrated separately, by hand, and recorded in
# _plans/verification-log.md, not baked into this script.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
APICURIO=http://localhost:8081
GROUP=apa
ARTIFACT=apa.orders.decisions
SCHEMA_FILE=../../contracts/19-cancel-gate-decision.schema.json
SVC=human-in-the-loop-gate
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

poll() { # poll <seconds> <command...>: retry every 2 s until the command succeeds
    local end=$((SECONDS + $1)); shift
    until "$@"; do
        (( SECONDS < end )) || return 1
        sleep 2
    done
}

healthy() { [[ "$(docker inspect -f '{{.State.Health.Status}}' "$1")" == "healthy" ]]; }

# --- the agent-facing surface
trigger_cancel() { # trigger_cancel <orderId> <reason>: prints the HTTP status, body in $TMP/trigger.json
    curl -s -o "$TMP/trigger.json" -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
        -d "{\"reason\":\"$2\"}" "$APP/orders/$1/cancel-request"
}
trigger_status_is() { [[ "$(trigger_cancel "$1" "$2")" == "$3" ]]; } # trigger_status_is <orderId> <reason> <code>

gate_field() { curl -sf "$APP/orders/$1/cancel-gate" | jq -r ".$2"; } # gate_field <orderId> <field>
gate_status_is() { [[ "$(gate_field "$1" status)" == "$2" ]]; }       # gate_status_is <orderId> <status>
no_gate_exists() { ! curl -sf "$APP/orders/$1/cancel-gate" >/dev/null; } # no_gate_exists <orderId>

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

topic_count_for_key() { # topic_count_for_key <topic> <key>: number of records on <topic> keyed by <key>
    docker exec apa-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
        --topic "$1" --from-beginning --timeout-ms 15000 --property print.key=true 2>/dev/null \
        | grep -c "^$2"
}
topic_count_for_key_is() { [[ "$(topic_count_for_key "$1" "$2")" == "$3" ]]; }

publish_decision() { # publish_decision <correlationId> <decision>
    printf '%s:{"correlationId":"%s","decision":"%s","decidedBy":"reviewer@example.com"}\n' "$1" "$1" "$2" \
        | docker exec -i apa-kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 \
            --topic apa.orders.decisions --property parse.key=true --property key.separator=: >/dev/null 2>&1
}

# --- Apicurio Registry (REST API v3)
register_schema() {
    local content; content="$(python3 -I -c 'import json,sys; print(json.dumps(sys.stdin.read()))' <"$SCHEMA_FILE")"
    curl -sf -X POST "$APICURIO/apis/registry/v3/groups/$GROUP/artifacts" -H 'Content-Type: application/json' \
        -d "{\"artifactId\":\"$ARTIFACT\",\"artifactType\":\"JSON\",\"firstVersion\":{\"content\":{\"content\":$content,\"contentType\":\"application/json\"}}}" \
        >"$TMP/register.json"
}
set_backward_compatibility() {
    curl -sf -X POST "$APICURIO/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT/rules" -H 'Content-Type: application/json' \
        -d '{"ruleType":"COMPATIBILITY","config":"BACKWARD"}' >/dev/null
}
artifact_registered() {
    curl -sf "$APICURIO/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT" | jq -e ".artifactId == \"$ARTIFACT\"" >/dev/null
}
compatibility_is_backward() {
    curl -sf "$APICURIO/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT/rules/COMPATIBILITY" \
        | jq -e '.config == "BACKWARD"' >/dev/null
}

# --- observability
tempo_has_trace() {
    curl -sf -G "http://localhost:3200/api/search" --data-urlencode "tags=service.name=$SVC" | grep '"traceID"' >/dev/null
}
prom_has_metric() {
    curl -sf -G "http://localhost:19090/api/v1/query" --data-urlencode "query=target_info{service_name=\"$SVC\"}" \
        | grep '"result":\[{' >/dev/null
}
prom_has_counter() {
    curl -sf "http://localhost:19090/api/v1/label/__name__/values" | grep -i 'cancelgate' >/dev/null
}
loki_has_log() { # loki_has_log <search string>
    local now; now=$(date +%s)
    curl -sf -G "http://localhost:3100/loki/api/v1/query_range" --data-urlencode "query={service_name=\"$SVC\"} |= \"$1\"" \
        --data-urlencode "start=$((now - 600))000000000" --data-urlencode "end=$((now + 60))000000000" \
        | grep '"values":\[\[' >/dev/null
}

# The model that drives the live tool-call round trip must already be pulled into the ollama
# volume, or the compose stack comes up with an assistant that cannot answer. This script never
# pulls.
../../scripts/ollama-model.sh check

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted run),
# then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-human-in-the-loop-gate; do
    check "$c is healthy" healthy "$c"
done

echo "Contract: registering the decision-event schema in Apicurio"
precondition "the schema registers in Apicurio" register_schema
precondition "the COMPATIBILITY rule is set to BACKWARD" set_backward_compatibility
check "the artifact $ARTIFACT exists in group $GROUP" artifact_registered
check "the artifact's compatibility rule reads back as BACKWARD" compatibility_is_backward

echo "Seed check"
check "seeded order O-19001 starts PLACED" order_status_is O-19001 PLACED
check "seeded payment for O-19001 starts COMPLETED" payment_status_is O-19001 COMPLETED

echo "Pause: the trigger opens a gate and holds the side effect"
precondition "POST /orders/O-19001/cancel-request is accepted (202)" \
    trigger_status_is O-19001 "customer requested cancellation" 202
CORR_1="$(gate_field O-19001 correlationId)"
check "the gate for O-19001 is PENDING" gate_status_is O-19001 PENDING
check "O-19001 is still PLACED: no side effect before a decision" order_status_is O-19001 PLACED
check "O-19001's payment is still COMPLETED: no refund before a decision" payment_status_is O-19001 COMPLETED
check "exactly one pending-decision record is keyed by the correlation id" \
    topic_count_for_key_is apa.orders.pending-decisions "$CORR_1" 1
check "no decision has been published yet for the correlation id" \
    topic_count_for_key_is apa.orders.decisions "$CORR_1" 0

echo "Resume: APPROVE performs the cancel-and-refund exactly once"
publish_decision "$CORR_1" APPROVE
check "the gate for O-19001 resumes to APPROVED_EXECUTED" poll 30 gate_status_is O-19001 APPROVED_EXECUTED
check "O-19001 is now CANCELLED" order_status_is O-19001 CANCELLED
check "O-19001's payment is now REFUNDED" payment_status_is O-19001 REFUNDED
check "exactly one execution is logged for the correlation id" execution_count_is "$CORR_1" 1

echo "Idempotency: redelivering the same APPROVE does not double-apply"
publish_decision "$CORR_1" APPROVE
sleep 5
check "O-19001 is still CANCELLED (unchanged by the redelivered APPROVE)" order_status_is O-19001 CANCELLED
check "O-19001's payment is still REFUNDED (unchanged by the redelivered APPROVE)" payment_status_is O-19001 REFUNDED
check "the execution log still shows exactly one execution for the correlation id" execution_count_is "$CORR_1" 1

echo "Resume: REJECT terminates with no side effect"
precondition "POST /orders/O-19002/cancel-request is accepted (202)" \
    trigger_status_is O-19002 "customer requested cancellation" 202
CORR_2="$(gate_field O-19002 correlationId)"
check "the gate for O-19002 is PENDING" gate_status_is O-19002 PENDING
publish_decision "$CORR_2" REJECT
check "the gate for O-19002 resumes to REJECTED_BY_USER" poll 30 gate_status_is O-19002 REJECTED_BY_USER
check "O-19002 is still PLACED: REJECT performs no side effect" order_status_is O-19002 PLACED
check "O-19002's payment is still COMPLETED: REJECT performs no side effect" payment_status_is O-19002 COMPLETED
check "no execution is ever logged for the rejected correlation id" execution_count_is "$CORR_2" 0

echo "Negative checks: the trigger validates before ever opening a gate"
check "an unknown order id is rejected with 404" trigger_status_is O-NEVER-PLACED "cancel it" 404
check "an already-cancelled order (O-19003) is rejected with 409" trigger_status_is O-19003 "cancel again" 409
check "no gate was ever opened for the already-cancelled order" no_gate_exists O-19003

echo "Observability"
check "Tempo has a trace for service $SVC" poll 90 tempo_has_trace
check "Prometheus has a metric from $SVC" poll 90 prom_has_metric
check "Prometheus has the cancel-gate counters" poll 90 prom_has_counter
check "Loki has the tool's own log line (observed, not model prose)" poll 90 loki_has_log "tool invoked: proposeCancelAndRefund"

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
