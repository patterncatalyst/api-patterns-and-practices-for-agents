#!/usr/bin/env bash
# Starts the stack with the agent-behaviour-eval service and checks that the deterministic
# BehaviourEvaluator scores a recorded transcript to PASS or FAIL against the committed
# contracts/28-behaviour-eval.json spec, never by asking a model whether its own run looked good:
# the compliant good-run.jsonl is scored PASS, the regressed regressed-run.jsonl is scored FAIL
# naming the call-budget-exceeded assertion, and every verdict is recorded durably in PostgreSQL.
# The mutation witness edits the good transcript's own refundCompleted flag and resubmits it
# against the SAME spec: the verdict flips to FAIL, proving the PASS was not vacuous.
# LIVE=1 drives GET /eval/run/good against the real pinned qwen3 model instead of replaying
# good-run.jsonl, scoring whatever it actually did against the same spec and budgets.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
SVC=agent-behaviour-eval
GOOD_FIXTURE=src/main/resources/fixtures/good-run.jsonl
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

differs() { ! cmp -s "$1" "$2"; } # differs <fileA> <fileB>

eval_column() { # eval_column <column> <runId>
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT $1 FROM eval.eval_runs WHERE run_id='$2' ORDER BY recorded_at DESC LIMIT 1"
}
verdict_is() { [[ "$(eval_column verdict "$1")" == "$2" ]]; } # verdict_is <runId> <verdict>
violated_assertion_is() { [[ "$(eval_column violated_assertion "$1")" == "$2" ]]; } # violated_assertion_is <runId> <assertion>
violated_assertion_is_null() { [[ -z "$(eval_column violated_assertion "$1")" ]]; } # violated_assertion_is_null <runId>

report_run() { # report_run <runId>: print one scored run's measured numbers
    eval_column transcript "$1" | jq -r \
        '"  \(.runId): toolCalls=\(.toolCalls | length) totalTokens=\(.totalTokens) refundCompleted=\(.refundCompleted) finalOrderStatus=\(.finalOrderStatus)"'
}

tempo_has_trace() {
    curl -sf -G "http://localhost:3200/api/search" --data-urlencode "tags=service.name=$SVC" | grep '"traceID"' >/dev/null
}
prom_has_metric() {
    curl -sf -G "http://localhost:19090/api/v1/query" --data-urlencode "query=target_info{service_name=\"$SVC\"}" \
        | grep '"result":\[{' >/dev/null
}
prom_has_counter() {
    curl -sf "http://localhost:19090/api/v1/label/__name__/values" | grep -i 'agentbehavioureval' >/dev/null
}
loki_has_log() { # loki_has_log <search string>
    local now; now=$(date +%s)
    curl -sf -G "http://localhost:3100/loki/api/v1/query_range" --data-urlencode "query={service_name=\"$SVC\"} |= \"$1\"" \
        --data-urlencode "start=$((now - 600))000000000" --data-urlencode "end=$((now + 60))000000000" \
        | grep '"values":\[\[' >/dev/null
}

if [[ "${LIVE:-0}" == "1" ]]; then
    # The model that drives the LIVE run must already be pulled into the shared ollama volume,
    # or the stack comes up with an assistant that cannot answer.
    ../../scripts/ollama-model.sh check
    export AGENT_EVAL_MODEL_SOURCE=ollama
else
    export AGENT_EVAL_MODEL_SOURCE=fixture
fi

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-agent-behaviour-eval; do
    check "$c is healthy" healthy "$c"
done

if [[ "${LIVE:-0}" == "1" ]]; then
    echo "LIVE good run: the real model performs the task, scored against the committed spec"
else
    echo "Good run: the compliant recorded transcript is scored PASS"
fi
check "GET /eval/run/good responds" curl -sf "$APP/eval/run/good"
check "the good run is recorded PASS (observed in PostgreSQL, not model prose)" \
    poll 60 verdict_is good-run PASS
check "the recorded PASS names no violated assertion" violated_assertion_is_null good-run
report_run good-run

echo "Regressed run: the committed regression is scored FAIL naming the call budget"
check "GET /eval/run/regressed responds" curl -sf "$APP/eval/run/regressed"
check "the regressed run is recorded FAIL" poll 60 verdict_is regressed-run FAIL
check "the recorded FAIL names the call-budget assertion" violated_assertion_is regressed-run call-budget-exceeded
report_run regressed-run

check "Tempo has a trace for service $SVC" poll 90 tempo_has_trace
check "Prometheus has a metric from $SVC" poll 90 prom_has_metric
check "Prometheus has the eval-recorded counter" poll 90 prom_has_counter
check "Loki has the recorder's own log line (observed, not model prose)" poll 90 loki_has_log "eval recorded"

echo "Mutation witness: the SAME spec flips to FAIL when the good transcript's own outcome is edited"
MUTATED=$(mktemp)
sed 's/"refundCompleted":true/"refundCompleted":false/' "$GOOD_FIXTURE" > "$MUTATED"
check "the mutated transcript differs from the committed one" differs "$MUTATED" "$GOOD_FIXTURE"
check "POST /eval of the mutated transcript responds" \
    curl -sf -X POST -H 'Content-Type: text/plain' --data-binary "@$MUTATED" "$APP/eval"
rm -f "$MUTATED"
check "the SAME spec now scores run good-run FAIL (non-vacuous: editing refundCompleted flips the verdict)" \
    poll 60 verdict_is good-run FAIL
check "the flipped verdict names the refund-not-completed assertion" \
    violated_assertion_is good-run refund-not-completed

echo "Restore: resubmitting the unmutated good transcript passes again, against the same spec"
check "GET /eval/run/good responds" curl -sf "$APP/eval/run/good"
check "the real good-run.jsonl is recorded PASS again" poll 60 verdict_is good-run PASS

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
