#!/usr/bin/env bash
# Starts the stack and checks the observable effects of auditing AI-using code: the INWARD
# counterpart to 21-replayable-agent-memory-event-sourced-audit. Every outbound call this app's
# own agent makes (to ch13's remote orderStatus MCP tool) must FIRST append an append-only audit
# record, THEN perform the call. Asserts: one POST /consume for ch13's seeded order produces
# exactly one audit row for that outbound call, and the business result carries ch13's
# UNGUESSABLE seeded tracking number FL-778821, proving the call really went out; a replay from
# the beginning of the audit log reproduces the live projection; the append-only guard rejects a
# mutate by default. Then a BITING NEGATIVE WITNESS wired into the normal run (env, no source
# edit): recreating app with AUDIT_INTERCEPTOR_ENABLED=false, the outbound call still succeeds
# (the business row is still written) but NO audit row is appended for it; restoring the flag
# resumes auditing. KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
APICURIO=http://localhost:8081
GROUP=apa
ARTIFACT=apa.audit.outbound-calls
SCHEMA_FILE=../../contracts/34-outbound-call-audit.schema.json
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

poll() { # poll <seconds> <command...>: retry every 3 s until the command succeeds
    local end=$((SECONDS + $1)); shift
    until "$@"; do
        (( SECONDS < end )) || return 1
        sleep 3
    done
}

healthy() { [[ "$(docker inspect -f '{{.State.Health.Status}}' "$1")" == "healthy" ]]; }
restart_app() { "${COMPOSE[@]}" up -d --no-deps --force-recreate --wait app >/dev/null; }
ready() { curl -sf "$APP/q/health/ready" >/dev/null; }

# --- the trigger
consume_order() { curl -sf -X POST -H 'Content-Type: text/plain' --data "$1" "$APP/consume" >/dev/null; } # consume_order <orderId>

# --- the business result: ch13 seeds exactly one order, O-9001, status SHIPPED, shipment
# tracking FL-778821. The tracking number is the witness value: the model has no way to produce
# it except by actually reaching the remote orderStatus tool.
SEEDED_ORDER=O-9001
SEEDED_STATUS=SHIPPED
SEEDED_TRACKING=FL-778821

result_column() { # result_column <column> <orderId>
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT $1 FROM consumer.outbound_call_result WHERE order_id='$2'"
}
clear_result() { # clear_result <orderId>
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -c \
        "DELETE FROM consumer.outbound_call_result WHERE order_id='$1'" >/dev/null
}
business_result_recorded() { # the row carries ch13's seeded status AND unguessable tracking number
    [[ "$(result_column order_status "$1")" == "$SEEDED_STATUS" \
        && "$(result_column tracking_number "$1")" == "$SEEDED_TRACKING" ]]
}

# --- the outbound-call audit log
audit_row_count() { curl -sf "$APP/audit/log/$1" | jq 'length'; } # audit_row_count <correlationId>
audit_count_is() { [[ "$(audit_row_count "$1")" == "$2" ]]; }     # audit_count_is <correlationId> <n>
audit_row_direction() { curl -sf "$APP/audit/log/$1" | jq -r '.[0].direction'; }
audit_row_target() { curl -sf "$APP/audit/log/$1" | jq -r '.[0].target'; }
audit_row_id() { curl -sf "$APP/audit/log/$1" | jq -r '.[0].id'; }
audit_row_digest() { curl -sf "$APP/audit/log/$1" | jq -r '.[0].argumentDigest'; }

mutate_status() { # mutate_status <id> <newDigest>: prints the HTTP status
    curl -s -o "$TMP/mutate.json" -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
        -d "{\"id\":\"$1\",\"newArgumentDigest\":\"$2\"}" "$APP/audit/demo/mutate"
}
mutate_rejected() { [[ "$(mutate_status "$1" tampered)" == 403 ]]; }

replay_matches_live() { curl -sf -X POST "$APP/audit/replay" | jq -e '.matchesLive == true' >/dev/null; }

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
SVC=auditing-ai-using-code
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
# volume, or the compose stack comes up with an agent that cannot answer. This script never pulls.
../../scripts/ollama-model.sh check

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted run),
# then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm \
         apa-34-package-api-as-mcp-tools apa-auditing-ai-using-code; do
    check "$c is healthy" healthy "$c"
done

echo "Contract: registering the outbound-call audit schema in Apicurio"
precondition "the schema registers in Apicurio" register_schema
precondition "the COMPATIBILITY rule is set to BACKWARD" set_backward_compatibility
check "the artifact $ARTIFACT exists in group $GROUP" artifact_registered
check "the artifact's compatibility rule reads back as BACKWARD" compatibility_is_backward

echo "Normal operation: one outbound call appends exactly one audit record, before the call completes"
precondition "POST /consume succeeds for $SEEDED_ORDER" consume_order "$SEEDED_ORDER"
check "the business result for $SEEDED_ORDER carries ch13's seeded status and UNGUESSABLE tracking (observed in PostgreSQL, not model prose)" \
    poll 120 business_result_recorded "$SEEDED_ORDER"
check "exactly one audit row exists for $SEEDED_ORDER" poll 30 audit_count_is "$SEEDED_ORDER" 1
check "the audit row's direction is OUTBOUND" bash -c "[[ \"\$(audit_row_direction $SEEDED_ORDER)\" == OUTBOUND ]]"
check "the audit row's target is orderStatus" bash -c "[[ \"\$(audit_row_target $SEEDED_ORDER)\" == orderStatus ]]"
check "a replay from the beginning of the log reproduces the live projection" poll 60 replay_matches_live

echo "Append-only, by default"
AUDIT_ID="$(audit_row_id "$SEEDED_ORDER")"
ORIGINAL_DIGEST="$(audit_row_digest "$SEEDED_ORDER")"
check "the log is append-only: a mutate attempt is rejected (403) while audit.mutable is false" mutate_rejected "$AUDIT_ID"
check "the rejected mutate attempt left the record unchanged" \
    bash -c "[[ \"\$(audit_row_digest $SEEDED_ORDER)\" == '$ORIGINAL_DIGEST' ]]"

echo "Biting negative witness: AUDIT_INTERCEPTOR_ENABLED=false still calls out, but appends no audit record"
clear_result "$SEEDED_ORDER"
AUDIT_INTERCEPTOR_ENABLED=false restart_app
precondition "the app is ready again with the audit interceptor disabled" poll 60 ready
precondition "the outbound call still succeeds with the interceptor disabled" consume_order "$SEEDED_ORDER"
check "the business result is still recorded with the interceptor disabled (the call itself still went out)" \
    poll 60 business_result_recorded "$SEEDED_ORDER"
check "negative witness: NO new audit row was appended for that call (still exactly 1, the one from before)" \
    audit_count_is "$SEEDED_ORDER" 1

echo "Restore: AUDIT_INTERCEPTOR_ENABLED=true again"
AUDIT_INTERCEPTOR_ENABLED=true restart_app
precondition "the app is ready again with the audit interceptor restored" poll 60 ready
precondition "a further outbound call succeeds" consume_order "$SEEDED_ORDER"
check "restored: auditing resumed, a second audit row now exists for $SEEDED_ORDER" poll 30 audit_count_is "$SEEDED_ORDER" 2
check "restored: a replay still matches the live projection" poll 60 replay_matches_live

echo "Observability"
check "Tempo has a trace for service $SVC" poll 90 tempo_has_trace
check "Prometheus has a metric from $SVC" poll 90 prom_has_metric
check "Loki has the fold's own log line (observed, never model prose)" poll 90 loki_has_log "folded into the live projection"

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
