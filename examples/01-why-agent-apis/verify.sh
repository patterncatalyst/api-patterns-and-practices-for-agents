#!/usr/bin/env bash
# Replays the committed, recorded transcript by default (CI-safe, no model and no containers
# needed) and asserts the measured contrast between the bad and good contract that is chapter
# 01's thesis. Set LIVE=1 to run the same task against the real Ollama model instead of
# replaying the fixture; KEEP=1 then leaves the LIVE stack running afterwards. See README for
# how the fixtures in fixtures/ were recorded.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

TOTAL=0
PASSED=0

check() { # check "<description>" <command...>
    local desc="$1"; shift
    TOTAL=$((TOTAL + 1))
    if "$@" >/dev/null 2>&1; then
        PASSED=$((PASSED + 1)); printf 'PASS %d: %s\n' "$TOTAL" "$desc"
    else
        printf 'FAIL %d: %s\n' "$TOTAL" "$desc"
    fi
}

report() { # report <run.json>: print one run's measured numbers
    jq -r '"  \(.contract): toolCalls=\(.toolCalls) argErrors=\(.argErrors) totalTokens=\(.totalTokens) refundCompleted=\(.refundCompleted)"' "$1"
}

assert_contrast() { # assert_contrast <good.json> <bad.json>
    local good="$1" bad="$2"
    local good_calls bad_calls good_errors good_refunded bad_tokens good_tokens
    good_calls=$(jq -r '.toolCalls' "$good")
    bad_calls=$(jq -r '.toolCalls' "$bad")
    good_errors=$(jq -r '.argErrors' "$good")
    good_refunded=$(jq -r '.refundCompleted' "$good")
    good_tokens=$(jq -r '.totalTokens' "$good")
    bad_tokens=$(jq -r '.totalTokens' "$bad")

    check "good contract completes the task in exactly 1 tool call" test "$good_calls" = "1"
    check "good contract raises 0 argument errors" test "$good_errors" = "0"
    check "good contract actually completes the refund" test "$good_refunded" = "true"
    check "bad contract needs more tool calls than the good contract ($bad_calls > $good_calls)" \
        test "$bad_calls" -gt "$good_calls"
    check "bad contract spends more tokens than the good contract ($bad_tokens > $good_tokens)" \
        test "$bad_tokens" -gt "$good_tokens"
    # The chapter's negative witness: forcing the good contract's own pass/fail rule (exactly
    # one tool call) onto the bad contract's recorded run must fail, proving the rule actually
    # discriminates between the two contracts rather than always passing.
    check "negative witness: the good contract's one-call rule fails when applied to the bad contract's run" \
        test "$bad_calls" != "1"
}

if [[ "${LIVE:-0}" == "1" ]]; then
    COMPOSE=(docker compose -f compose.yaml)
    APP=http://localhost:8080
    GOOD=""
    BAD=""

    cleanup() {
        [[ -n "$GOOD" ]] && rm -f "$GOOD"
        [[ -n "$BAD" ]] && rm -f "$BAD"
        if [[ "${KEEP:-0}" == "1" ]]; then
            echo "KEEP=1: stack left running; run \`docker compose -f compose.yaml down -v\` in this directory to remove it"
        else
            "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
        fi
    }
    trap cleanup EXIT

    healthy() { [[ "$(docker inspect -f '{{.State.Health.Status}}' "$1")" == "healthy" ]]; }

    # The model that drives both assistants must already be pulled into the ollama volume, or
    # the compose stack comes up with an assistant that cannot answer.
    ../../scripts/ollama-model.sh check

    # Fresh start: remove any containers and volumes left by an earlier run, then create every
    # container and anonymous volume anew.
    "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
    "${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

    for c in apa-ollama apa-why-agent-apis; do
        check "$c is healthy" healthy "$c"
    done

    GOOD=$(mktemp)
    BAD=$(mktemp)
    check "the good contract run responds" curl -sf -o "$GOOD" "$APP/run/good"
    check "the bad contract run responds" curl -sf -o "$BAD" "$APP/run/bad"

    echo "LIVE run (compare against the recorded fixtures in fixtures/):"
    report "$GOOD"
    report "$BAD"
    assert_contrast "$GOOD" "$BAD"
else
    echo "Replaying the recorded transcript in fixtures/ (set LIVE=1 to run the real model instead):"
    report fixtures/good-run.json
    report fixtures/bad-run.json
    assert_contrast fixtures/good-run.json fixtures/bad-run.json
fi

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
