#!/usr/bin/env bash
# Starts the stack with the ag-structured-outputs service and runs the SAME extraction prompt N
# times against two paths over the SAME model: /extract/typed (TypedIntentExtractor, whose declared
# ExtractedIntent return type gets Ollama's format grammar-constrained decoding) and
# /extract/untyped (UntypedIntentExtractor, a bare String, no schema). The claim under test is
# consistency of SHAPE and CLASS, never determinism of bytes: the typed path must parse into
# ExtractedIntent with intent in the allowed set on every run; the untyped path is only asserted to
# drift (fail that same strict parse) on at least one of the N runs, since a model that happens to
# behave well every time would make nothing here a counterexample to the thesis, only a quieter
# run of it. Ends with the negative witness: the same /extract/typed endpoint, with
# AGENT_EXTRACTION_STRUCTURED_OUTPUT_ENABLED=false, breaking its own downstream parse. KEEP=1
# leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
SVC=apa-ag-structured-outputs
RUNS=5
MESSAGE="Hi, this is about order O-4821. It arrived two days late and one item was damaged, so I'd like a refund of \$38.50 please."
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

restart_app() { "${COMPOSE[@]}" up -d --no-deps --force-recreate --wait app >/dev/null; }

extract() { # extract <path> -> prints the response body regardless of HTTP status: a parseOk=false
    # 422 from /extract/typed is itself an assertable outcome, not a transport failure, so this
    # never passes -f to curl (which would discard that very body on a non-2xx status).
    curl -s -X POST -H 'Content-Type: text/plain' --data-binary "$MESSAGE" \
        "http://localhost:8080/extract/$1" 2>/dev/null || true
}

ALLOWED_INTENTS='^(STATUS_INQUIRY|REFUND_REQUEST|CANCEL_REQUEST|COMPLAINT|OTHER)$'

typed_run_is_shape_stable() { # typed_run_is_shape_stable <response-json>
    local body="$1"
    [[ -n "$body" ]] || return 1
    jq -e '.parseOk == true' <<<"$body" >/dev/null 2>&1 || return 1
    jq -e '.parsed.orderId == "O-4821"' <<<"$body" >/dev/null 2>&1 || return 1
    jq -e --arg re "$ALLOWED_INTENTS" '.parsed.intent | test($re)' <<<"$body" >/dev/null 2>&1
}

untyped_run_parses_cleanly() { # untyped_run_parses_cleanly <response-json>
    local body="$1"
    [[ -n "$body" ]] || return 1
    jq -e '.parseOk == true' <<<"$body" >/dev/null 2>&1
}

disabled_witness_holds() { # disabled_witness_holds <response-json>: the schema constraint off,
    # the same endpoint reports it was off and that its own downstream parse failed.
    local body="$1"
    [[ -n "$body" ]] || return 1
    jq -e '.parseOk == false and .structuredOutputEnabled == false' <<<"$body" >/dev/null 2>&1
}

# The model that drives both live AiService calls must already be pulled into the ollama volume,
# or the compose stack comes up with an assistant that cannot answer. This script never pulls.
../../scripts/ollama-model.sh check

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted run),
# then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm "$SVC"; do
    check "$c is healthy" healthy "$c"
done

# Phase 1: the same prompt, RUNS times, on each path, against the default (structured output on).
typed_stable=0
for i in $(seq 1 "$RUNS"); do
    body=$(extract typed)
    if typed_run_is_shape_stable "$body"; then
        typed_stable=$((typed_stable + 1))
    else
        echo "typed run $i did not come back shape-stable: $body"
    fi
done
check "the typed path (/extract/typed) parses into ExtractedIntent with intent in the allowed set on all $RUNS runs" \
    [ "$typed_stable" -eq "$RUNS" ]

untyped_clean=0
for i in $(seq 1 "$RUNS"); do
    body=$(extract untyped)
    if untyped_run_parses_cleanly "$body"; then
        untyped_clean=$((untyped_clean + 1))
    else
        echo "untyped run $i did not parse cleanly (this is the expected drift, not a bug): $body"
    fi
done
check "the untyped path (/extract/untyped) drifts: it fails the same strict parse on at least one of $RUNS runs" \
    [ "$untyped_clean" -lt "$RUNS" ]

# Phase 2: the negative witness. The same /extract/typed endpoint, same model, same prompt, with
# the schema constraint turned off, loses its own shape guarantee.
AGENT_EXTRACTION_STRUCTURED_OUTPUT_ENABLED=false restart_app
disabled_body=$(extract typed)
echo "disabled run response: $disabled_body"
check "negative witness: /extract/typed with the schema constraint disabled reports parseOk=false" \
    disabled_witness_holds "$disabled_body"

# Restore: the same endpoint, same prompt, schema constraint back on, parses cleanly again.
AGENT_EXTRACTION_STRUCTURED_OUTPUT_ENABLED=true restart_app
restored_body=$(extract typed)
check "restored: /extract/typed parses cleanly again once the schema constraint is back on" \
    typed_run_is_shape_stable "$restored_body"

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
