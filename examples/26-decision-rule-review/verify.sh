#!/usr/bin/env bash
# Starts the stack with the decision-rule-review service and checks that POST /review, given a
# recorded agent run (a refund proposal plus the order's own facts), is decided by
# DecisionRuleReviewer, a deterministic, hand-rolled JSON-rule evaluator over the committed
# contracts/26-refund-review-rule.json, never by a model: the compliant recorded run is approved,
# the violating recorded run is rejected and names the clause that failed, and every verdict is
# recorded durably in PostgreSQL. The negative witness points the reviewer at the weakened rule
# file (the same rule with its amount-ceiling clause removed, via the REVIEW_RULE_PATH demo-code
# override) and replays the SAME violating run: it is wrongly approved, proving that clause is
# exactly what caught it. The real rule is then restored and re-asserted.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
SVC=decision-rule-review
RULE_PATH=/deployments/contracts/26-refund-review-rule.json
WEAKENED_RULE_PATH=/deployments/contracts/26-refund-review-rule.weakened.json
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

submit_fixture() { # submit_fixture <fixtureFile>
    curl -sf -X POST -H 'Content-Type: application/json' --data "@$1" "$APP/review" >/dev/null
}

review_column() { # review_column <column> <orderId>
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT $1 FROM review.reviews WHERE order_id='$2' ORDER BY reviewed_at DESC LIMIT 1"
}
verdict_is() { [[ "$(review_column verdict "$1")" == "$2" ]]; } # verdict_is <orderId> <verdict>
failed_clause_is() { [[ "$(review_column failed_clause "$1")" == "$2" ]]; } # failed_clause_is <orderId> <clause>
clear_reviews() {
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -c \
        "DELETE FROM review.reviews" >/dev/null
}

tempo_has_trace() {
    curl -sf -G "http://localhost:3200/api/search" --data-urlencode "tags=service.name=$SVC" | grep '"traceID"' >/dev/null
}
prom_has_metric() {
    curl -sf -G "http://localhost:19090/api/v1/query" --data-urlencode "query=target_info{service_name=\"$SVC\"}" \
        | grep '"result":\[{' >/dev/null
}
prom_has_counter() {
    curl -sf "http://localhost:19090/api/v1/label/__name__/values" | grep -i 'decisionrulereview' >/dev/null
}
loki_has_log() { # loki_has_log <search string>
    local now; now=$(date +%s)
    curl -sf -G "http://localhost:3100/loki/api/v1/query_range" --data-urlencode "query={service_name=\"$SVC\"} |= \"$1\"" \
        --data-urlencode "start=$((now - 600))000000000" --data-urlencode "end=$((now + 60))000000000" \
        | grep '"values":\[\[' >/dev/null
}

restart_app() { "${COMPOSE[@]}" up -d --no-deps --force-recreate --wait app >/dev/null; }

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-decision-rule-review; do
    check "$c is healthy" healthy "$c"
done

echo "Compliant recorded run: the deterministic reviewer approves it"
submit_fixture src/main/resources/fixtures/compliant-run.json
check "the compliant run O-26001 is recorded APPROVE (observed in PostgreSQL, not model prose)" \
    poll 60 verdict_is O-26001 APPROVE

echo "Violating recorded run: the reviewer rejects it and names the clause that failed"
submit_fixture src/main/resources/fixtures/violating-run.json
check "the violating run O-26002 is recorded REJECT" poll 60 verdict_is O-26002 REJECT
check "the recorded REJECT names the amount-ceiling clause" failed_clause_is O-26002 amount-exceeds-paid

check "Tempo has a trace for service $SVC" poll 90 tempo_has_trace
check "Prometheus has a metric from $SVC" poll 90 prom_has_metric
check "Prometheus has the review-recorded counter" poll 90 prom_has_counter
check "Loki has the recorder's own log line (observed, not model prose)" poll 90 loki_has_log "review recorded"

echo "Weakened-reviewer negative witness: the amount-ceiling clause is exactly what caught it"
clear_reviews
REVIEW_RULE_PATH=$WEAKENED_RULE_PATH restart_app
submit_fixture src/main/resources/fixtures/violating-run.json
check "with the weakened rule (no amount-ceiling clause), the SAME violating run is wrongly approved" \
    poll 60 verdict_is O-26002 APPROVE

echo "Restore: the real rule is back, the violating run is rejected again"
clear_reviews
REVIEW_RULE_PATH=$RULE_PATH restart_app
submit_fixture src/main/resources/fixtures/violating-run.json
check "restored: the real rule rejects O-26002 again, naming the same clause" \
    poll 60 failed_clause_is O-26002 amount-exceeds-paid

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
