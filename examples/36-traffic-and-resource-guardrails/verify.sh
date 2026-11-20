#!/usr/bin/env bash
# Starts the stack and checks that POST /ingest/orders enforces both guardrails: a valid payload
# is accepted; an invalid one is rejected with a 400 problem+json naming its violations and a
# durable trafficguardrails.rejections row; a burst beyond the 5-per-30-second rate limit gets a
# 429 problem+json on the 6th call, with its own durable row and Prometheus metric. The committed
# ingest schema is registered in Apicurio and read back. Two negative witnesses, each restored and
# reverified: (a) a generous rate-limit override makes the 6th call a 2xx instead of a 429; (b)
# disabling the schema gate makes the invalid payload a 2xx instead of a 400.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
APICURIO=http://localhost:8081
GROUP=apa36
ARTIFACT=apa.ingest.order
SCHEMA_FILE=../../contracts/36-ingest.schema.json

VALID_PAYLOAD='{"orderId":"O-9101","customerId":"C-4821","itemSku":"SKU-7734","quantity":2,"amount":19.98}'
INVALID_PAYLOAD='{"orderId":"O-9102","itemSku":"SKU-7734","quantity":0,"amount":19.98}'

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

new_tmp_file() { local f; f=$(mktemp); TMP_FILES+=("$f"); printf '%s' "$f"; }

restart_app() { "${COMPOSE[@]}" up -d --no-deps --force-recreate --wait app >/dev/null; }

STATUS=""
BODY_FILE=""
CTYPE=""
ingest() { # ingest <payload-json>: sets STATUS, BODY_FILE, CTYPE
    local header_file; header_file=$(new_tmp_file)
    BODY_FILE=$(new_tmp_file)
    STATUS=$(curl -s -D "$header_file" -o "$BODY_FILE" -w '%{http_code}' -X POST \
        -H 'Content-Type: application/json' -d "$1" "$APP/ingest/orders")
    CTYPE=$(grep -i '^content-type:' "$header_file" | tr -d '\r' | awk '{print $2}')
}

valid_payload_accepted() {
    ingest "$VALID_PAYLOAD"
    [[ "$STATUS" == "202" ]] && jq -e '.status == "ACCEPTED" and .orderId == "O-9101"' "$BODY_FILE" >/dev/null
}

invalid_payload_rejected() {
    ingest "$INVALID_PAYLOAD"
    [[ "$STATUS" == "400" ]] && [[ "$CTYPE" == application/problem+json* ]] \
        && jq -e '.type == "https://errors.patterncatalyst.dev/problems/ingest-schema-invalid" and (.violations | length) > 0' \
            "$BODY_FILE" >/dev/null
}

invalid_payload_accepted() { # negative witness (b): the schema gate disabled
    ingest "$INVALID_PAYLOAD"
    [[ "$STATUS" == 2?? ]]
}

three_more_valid_calls_ok() { # with the limit at 5 per window and 2 calls already made above,
                               # these are calls 3, 4 and 5: all still inside the limit
    for _ in 1 2 3; do
        ingest "$VALID_PAYLOAD"
        [[ "$STATUS" == "202" ]] || return 1
    done
    return 0
}

sixth_call_rate_limited() {
    ingest "$VALID_PAYLOAD"
    [[ "$STATUS" == "429" ]] && [[ "$CTYPE" == application/problem+json* ]] \
        && jq -e '.type == "https://errors.patterncatalyst.dev/problems/ingest-rate-limited"' "$BODY_FILE" >/dev/null
}

six_calls_all_accepted() { # negative witness (a): the rate limit overridden to a no-op
    for _ in 1 2 3 4 5 6; do
        ingest "$VALID_PAYLOAD"
        [[ "$STATUS" == "202" ]] || return 1
    done
    return 0
}

verify_rate_limit_enforced() { # self-contained: 5 calls accepted, the 6th rejected, from a fresh
                                # container (the rate-limit singleton starts at zero on every boot)
    for _ in 1 2 3 4 5; do
        ingest "$VALID_PAYLOAD"
        [[ "$STATUS" == "202" ]] || return 1
    done
    ingest "$VALID_PAYLOAD"
    [[ "$STATUS" == "429" ]] && [[ "$CTYPE" == application/problem+json* ]] \
        && jq -e '.type == "https://errors.patterncatalyst.dev/problems/ingest-rate-limited"' "$BODY_FILE" >/dev/null
}

rejection_count() { # rejection_count <reason>
    docker exec -e PGPASSWORD=apa apa-postgres psql -h 127.0.0.1 -U apa -d apa -tAc \
        "SELECT count(*) FROM trafficguardrails.rejections WHERE reason = '$1'" | tr -d '[:space:]'
}
rows_grew_beyond() { [[ "$(rejection_count "$2")" -gt "$1" ]]; } # rows_grew_beyond <baseline> <reason>

prom_has_rejection_metric() { # prom_has_rejection_metric <reason>
    curl -sf -G "http://localhost:19090/api/v1/query" --data-urlencode "query=ingest_rejected_total{reason=\"$1\"} > 0" \
        | grep '"result":\[{' >/dev/null
}
loki_has_log() { # loki_has_log <search string>
    local now; now=$(date +%s)
    curl -sf -G "http://localhost:3100/loki/api/v1/query_range" \
        --data-urlencode "query={service_name=\"traffic-and-resource-guardrails\"} |= \"$1\"" \
        --data-urlencode "start=$((now - 600))000000000" --data-urlencode "end=$((now + 60))000000000" \
        | grep '"values":\[\[' >/dev/null
}

register_schema() {
    local content; content=$(python3 -I -c 'import json,sys; print(json.dumps(sys.stdin.read()))' <"$SCHEMA_FILE")
    curl -sf -X POST "$APICURIO/apis/registry/v3/groups/$GROUP/artifacts" -H 'Content-Type: application/json' \
        -d "{\"artifactId\":\"$ARTIFACT\",\"artifactType\":\"JSON\",\"firstVersion\":{\"content\":{\"content\":$content,\"contentType\":\"application/json\"}}}" \
        >/dev/null
}
artifact_registered() {
    curl -sf "$APICURIO/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT" | jq -e ".artifactId == \"$ARTIFACT\"" >/dev/null
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted run),
# then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-traffic-and-resource-guardrails; do
    check "$c is healthy" healthy "$c"
done

precondition "the committed ingest schema registers in Apicurio group $GROUP" register_schema
check "the artifact $ARTIFACT exists in group $GROUP" artifact_registered

check "a valid payload is accepted (202, ACCEPTED)" valid_payload_accepted
schema_rows_before=$(rejection_count schema_invalid)
check "an invalid payload is rejected (400 problem+json, violations named)" invalid_payload_rejected
check "the schema-invalid rejection leaves a new durable row" rows_grew_beyond "$schema_rows_before" schema_invalid

check "3 more calls (the window's 3rd, 4th and 5th) are still accepted" three_more_valid_calls_ok
rate_limit_rows_before=$(rejection_count rate_limit)
check "the 6th call in the window is rate-limited (429 problem+json)" sixth_call_rate_limited
check "the rate-limit rejection leaves a new durable row" rows_grew_beyond "$rate_limit_rows_before" rate_limit
check "Prometheus has a nonzero ingest_rejected_total{reason=\"rate_limit\"}" poll 60 prom_has_rejection_metric rate_limit
check "Prometheus has a nonzero ingest_rejected_total{reason=\"schema_invalid\"}" poll 60 prom_has_rejection_metric schema_invalid
check "Loki has the recorder's rate-limit log line" poll 60 loki_has_log "ingest rejected: reason=rate_limit"
check "Loki has the recorder's schema-invalid log line" poll 60 loki_has_log "ingest rejected: reason=schema_invalid"

echo "--- negative witness (a): a generous rate-limit override ---"
RATE_LIMIT_VALUE_OVERRIDE=100000 RATE_LIMIT_WINDOW_OVERRIDE=30 restart_app
check "with the limit overridden, 6 calls in a row are all accepted (the over-limit check bites)" six_calls_all_accepted
echo "--- restoring the rate limit ---"
restart_app
check "the rate limit is enforced again after restore (5 accepted, the 6th rejected)" verify_rate_limit_enforced

echo "--- negative witness (b): the schema gate disabled ---"
SCHEMA_GATE_ENABLED=false restart_app
check "with the gate disabled, the invalid payload is accepted (the schema-gate check bites)" invalid_payload_accepted
echo "--- restoring the schema gate ---"
restart_app
check "the invalid payload is rejected again after restore" invalid_payload_rejected

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
