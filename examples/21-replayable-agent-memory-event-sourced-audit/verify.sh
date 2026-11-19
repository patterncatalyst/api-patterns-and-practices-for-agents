#!/usr/bin/env bash
# Starts the stack and checks the observable effects of an append-only, event-sourced audit log:
# the live projection is a pure fold of apa.audit.agent-actions, a replay from the beginning of
# the same log reproduces the live projection exactly, both audit directions (an inbound tool
# call the agent consumed, an outbound model call its langchain4j code made) appear in the log,
# a committed record cannot be mutated by default, and the audit-event schema is registered in
# Apicurio under the BACKWARD compatibility rule. AUDIT_MUTABLE=true is the negative witness: it
# lets a committed projection row be overwritten in place, bypassing the log, so a fresh replay
# diverges from the (now-corrupted) live state; rebuilding the live projection from the
# untouched log then proves the corruption was only ever in the projection, never in the log.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
APICURIO=http://localhost:8081
GROUP=apa
ARTIFACT=apa.audit.agent-actions
SCHEMA_FILE=../../contracts/21-audit-agent-action.schema.json
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
restart_app() { "${COMPOSE[@]}" up -d --no-deps --force-recreate --wait app >/dev/null; }
ready() { curl -sf "$APP/q/health/ready" >/dev/null; }

# --- the agent-facing surface
shipment_advice() { # shipment_advice <orderId> <identity>
    curl -sf -X POST -H 'Content-Type: application/json' \
        -d "{\"orderId\":\"$1\",\"identity\":\"$2\"}" "$APP/audit/shipment-advice" >"$TMP/advice.json"
}
log_row_count() { # log_row_count <orderId>
    curl -sf "$APP/audit/log/$1" | jq 'length'
}
log_has_count() { [[ "$(log_row_count "$1")" == "$2" ]]; } # log_has_count <orderId> <n>
log_directions() { # log_directions <orderId>: sorted, comma-joined direction set
    curl -sf "$APP/audit/log/$1" | jq -r '[.[].direction] | sort | join(",")'
}
both_directions_present() { [[ "$(log_directions "$1")" == "INBOUND,OUTBOUND" ]]; }
outbound_id_for() { # outbound_id_for <orderId>
    curl -sf "$APP/audit/log/$1" | jq -r '.[] | select(.direction == "OUTBOUND") | .id'
}
outbound_detail_for() { curl -sf "$APP/audit/log/$1" | jq -r '.[] | select(.direction == "OUTBOUND") | .detail'; }
detail_not_tampered() { [[ "$(outbound_detail_for "$1")" != *tampered* ]]; }        # detail_not_tampered <orderId>
detail_is_corrupted() { [[ "$(outbound_detail_for "$1")" == corrupted-by-demo ]]; } # detail_is_corrupted <orderId>
detail_is_not_corrupted() { [[ "$(outbound_detail_for "$1")" != corrupted-by-demo ]]; }

mutate_status() { # mutate_status <id> <newDetail>: prints the HTTP status
    curl -s -o "$TMP/mutate.json" -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
        -d "{\"id\":\"$1\",\"newDetail\":\"$2\"}" "$APP/audit/demo/mutate"
}
mutate_rejected() { [[ "$(mutate_status "$1" tampered)" == 403 ]]; }
mutate_accepted() { [[ "$(mutate_status "$1" "$2")" == 200 ]]; }

replay_matches_live() {
    curl -sf -X POST "$APP/audit/replay" | jq -e '.matchesLive == true' >/dev/null
}
replay_diverges_from_live() {
    curl -sf -X POST "$APP/audit/replay" | jq -e '.matchesLive == false' >/dev/null
}
rebuild_live() { curl -sf -X POST "$APP/audit/rebuild-live" >/dev/null; }

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
loki_has_log() { # loki_has_log <search string>
    local now; now=$(date +%s)
    curl -sf -G "http://localhost:3100/loki/api/v1/query_range" --data-urlencode "query={service_name=\"$SVC\"} |= \"$1\"" \
        --data-urlencode "start=$((now - 600))000000000" --data-urlencode "end=$((now + 60))000000000" \
        | grep '"values":\[\[' >/dev/null
}

SVC=replayable-agent-memory-event-sourced-audit

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-replayable-agent-memory-event-sourced-audit; do
    check "$c is healthy" healthy "$c"
done

echo "Contract: registering the audit-event schema in Apicurio"
precondition "the schema registers in Apicurio" register_schema
precondition "the COMPATIBILITY rule is set to BACKWARD" set_backward_compatibility
check "the artifact $ARTIFACT exists in group $GROUP" artifact_registered
check "the artifact's compatibility rule reads back as BACKWARD" compatibility_is_backward

echo "Normal operation: one shipment-advice call appends both audit directions"
precondition "POST /audit/shipment-advice succeeds for O-21001" shipment_advice O-21001 agent:shipping-ui
check "the live projection has exactly 2 rows for O-21001" poll 30 log_has_count O-21001 2
check "both audit directions (INBOUND and OUTBOUND) appear for O-21001" both_directions_present O-21001
check "a replay from the beginning of the log reproduces the live projection" poll 60 replay_matches_live

echo "Append-only, by default"
OUTBOUND_ID="$(outbound_id_for O-21001)"
check "the log is append-only: a mutate attempt is rejected (403) while audit.mutable is false" \
    mutate_rejected "$OUTBOUND_ID"
check "the rejected mutate attempt left the record unchanged" detail_not_tampered O-21001

echo "Negative witness: AUDIT_MUTABLE=true lets a committed record be overwritten in place"
AUDIT_MUTABLE=true restart_app
precondition "the app is ready again with audit.mutable=true" poll 60 ready
precondition "the demo mutate now succeeds and overwrites the committed record" \
    mutate_accepted "$OUTBOUND_ID" "corrupted-by-demo"
check "the live projection now shows the corrupted detail for O-21001" detail_is_corrupted O-21001
check "negative witness: a replay from the untouched log no longer matches the (corrupted) live projection" \
    poll 30 replay_diverges_from_live

echo "Restore: audit.mutable=false again, then recover by rebuilding the live projection from the log"
AUDIT_MUTABLE=false restart_app
precondition "the app is ready again with audit.mutable=false" poll 60 ready
precondition "a further mutate attempt is rejected again" mutate_rejected "$OUTBOUND_ID"
precondition "rebuilding the live projection from the log succeeds" rebuild_live
check "the rebuilt live projection no longer carries the corrupted detail" detail_is_not_corrupted O-21001
check "restored: a replay matches the rebuilt live projection again" poll 30 replay_matches_live

echo "Observability"
check "Tempo has a trace for service $SVC" poll 90 tempo_has_trace
check "Prometheus has a metric from $SVC" poll 90 prom_has_metric
check "Loki has the fold's own log line (observed, never model prose)" poll 90 loki_has_log "folded into the live projection"

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
