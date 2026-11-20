#!/usr/bin/env bash
#
# Deploys the reused event-triggered-agent-wakeup service (examples/17-event-triggered-agent-
# wakeup, same image, unchanged) onto the shared examples/_k8s platform and checks that the
# pattern it proves holds through Strimzi Kafka and CloudNativePG Postgres, not just through
# compose: a payment-processed event wakes the agent instead of the service polling for it, its
# one prepShipment tool call writes a durable shipment_prep record, and that is observable only
# through the row, its own counter, log line and span, never the model's prose. Includes the
# cluster-native negative witness: with the trigger consumer disabled (an env var flip on the
# Deployment, triggering its own rollout), the same event sits unconsumed and no record is
# written, until the consumer is restored and reads what it missed.
#
# Assumes the platform (examples/_k8s/bootstrap.sh) is already up; this script only gates on
# readiness and tells the reader to run it otherwise, rather than calling it itself (bootstrap is
# idempotent but slow, and this script owns the application layer only). Every host-facing check
# goes through a platform NodePort or `kubectl exec`; nothing here forwards, tunnels or proxies.
#
# KEEP=1 leaves this app's Deployment and Service running afterwards (the shared platform is
# never torn down by this script either way).
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")" || exit 1

K8S_DIR="../_k8s"
# shellcheck source=../_k8s/lib/endpoints.sh
source "${K8S_DIR}/lib/endpoints.sh"

APP_NS="apa"
DATA_NS="$EP_DATA_NS"
OBS_NS="$EP_OBS_NS"
DEPLOY_NAME="event-triggered-agent-wakeup"
SVC="$DEPLOY_NAME"
APP_OVERLAY="${K8S_DIR}/overlays/minikube/apps/17-event-triggered-agent-wakeup"
TOPIC="apa.payments.processed"
TOTAL=0
PASSED=0

require_tool() {
    local tool="$1" hint="$2"
    command -v "$tool" >/dev/null 2>&1 || { printf 'ERROR: %s is not in PATH.\n%s\n' "$tool" "$hint" >&2; exit 1; }
}
require_tool kubectl 'Install kubectl: https://kubernetes.io/docs/tasks/tools/'
require_tool kcat 'Install kcat (librdkafka-based Kafka CLI), for example: dnf install kcat'

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

# ─── Readiness gate: the shared platform, not this app ──────────────────────────────────────────
current_context="$(kubectl config current-context 2>/dev/null || echo "")"
if [[ "$current_context" != "$EP_PROFILE" ]]; then
    printf 'ERROR: kubectl context is "%s", expected "%s".\n' "$current_context" "$EP_PROFILE" >&2
    printf 'Run ../_k8s/setup-profile.sh and ../_k8s/bootstrap.sh first.\n' >&2
    exit 1
fi

platform_ready() {
    kubectl get namespace "$APP_NS" >/dev/null 2>&1 || return 1
    kubectl get configmap -n "$APP_NS" apa-app-config >/dev/null 2>&1 || return 1
    [[ "$(kubectl get kafka apa -n "$DATA_NS" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)" == "True" ]] || return 1
    [[ "$(kubectl get pods -n "$DATA_NS" -l cnpg.io/cluster=apa-postgres,cnpg.io/instanceRole=primary -o jsonpath='{.items[0].status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)" == "True" ]] || return 1
    [[ "$(kubectl get deployment otel-lgtm -n "$OBS_NS" -o jsonpath='{.status.conditions[?(@.type=="Available")].status}' 2>/dev/null)" == "True" ]] || return 1
    [[ "$(kubectl get deployment ollama -n "$DATA_NS" -o jsonpath='{.status.conditions[?(@.type=="Available")].status}' 2>/dev/null)" == "True" ]] || return 1
}
if ! platform_ready; then
    printf 'ERROR: the examples/_k8s platform is not Ready.\n' >&2
    printf 'Run ../_k8s/setup-profile.sh then ../_k8s/bootstrap.sh, then re-run this script.\n' >&2
    exit 1
fi

# ─── App-layer fresh deploy: this app only, never the platform ──────────────────────────────────
check "event-triggered-agent-wakeup deployed fresh and rolled out Ready in the apa namespace" bash -c '
    kubectl delete -k "$1" --ignore-not-found &&
    kubectl apply -k "$1" &&
    kubectl rollout status "deploy/$2" -n "$3" --timeout=300s
' _ "$APP_OVERLAY" "$DEPLOY_NAME" "$APP_NS"

# ─── Helpers: the Kafka trigger, over the platform's Kafka NodePort ──────────────────────────────
produce_payment() { # produce_payment <paymentId> <orderId> <amount>
    printf '%s:{"id":"%s","orderId":"%s","amount":%s,"status":"COMPLETED"}\n' "$2" "$1" "$2" "$3" \
        | kcat -P -b "127.0.0.1:${TP_KAFKA}" -t "$TOPIC" -K:
}

# ─── Helpers: the durable effect, read via kubectl exec psql (never a host NodePort connection) ──
PG_POD="$(kubectl get pods -n "$DATA_NS" -l cnpg.io/cluster=apa-postgres,cnpg.io/instanceRole=primary -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)"
PG_PASS="$(kubectl get secret -n "$DATA_NS" apa-postgres-app -o jsonpath='{.data.password}' 2>/dev/null | base64 -d)"

psql_remote() { # psql_remote <SQL with :'name' placeholders> [-v name=value ...]
    # The password reaches psql only via the remote shell's own `read` from stdin, never as a
    # command-line argument of any process (kubectl exec, bash -c or psql), so it never appears in
    # a `ps aux` listing on either side. The SQL text and -v values are passed as real argv
    # elements of the remote bash -c invocation, never string-interpolated into the script text,
    # so an order id cannot inject SQL; psql's own `:'name'` substitution quotes the value.
    local sql="$1"; shift
    printf '%s\n' "$PG_PASS" | kubectl exec -i -n "$DATA_NS" "$PG_POD" -- \
        bash -c 'sql="$1"; shift; read -r pw; PGPASSWORD="$pw" exec psql -h 127.0.0.1 -U apa -d apa -v ON_ERROR_STOP=1 "$@" -tAc "$sql"' \
        _ "$sql" "$@"
}
shipment_prep_carrier() { # shipment_prep_carrier <orderId>
    psql_remote "SELECT carrier FROM shipping.shipment_prep WHERE order_id = :'orderid'" -v "orderid=$1"
}
shipment_prep_is_a_valid_carrier() { # the live model decides which carrier; only the shape is asserted, never its prose
    local c; c=$(shipment_prep_carrier "$1")
    [[ "$c" == "FASTLINE" || "$c" == "AIRCO" ]]
}
no_shipment_prep() { [[ -z "$(shipment_prep_carrier "$1")" ]]; }

# ─── Helpers: traces, metrics and logs, over the platform's own Tempo/Prometheus/Loki NodePorts ──
tempo_has_trace() {
    curl -sf -G "http://127.0.0.1:${TP_TEMPO}/api/search" --data-urlencode "tags=service.name=$SVC" | grep '"traceID"' >/dev/null
}
prom_has_metric() {
    curl -sf -G "http://127.0.0.1:${TP_PROMETHEUS}/api/v1/query" --data-urlencode "query=target_info{service_name=\"$SVC\"}" \
        | grep '"result":\[{' >/dev/null
}
prom_has_counter() {
    curl -sf "http://127.0.0.1:${TP_PROMETHEUS}/api/v1/label/__name__/values" | grep -i 'agentwakeup' >/dev/null
}
loki_has_log() { # loki_has_log <search string>
    local now; now=$(date +%s)
    curl -sf -G "http://127.0.0.1:${TP_LOKI}/loki/api/v1/query_range" --data-urlencode "query={service_name=\"$SVC\"} |= \"$1\"" \
        --data-urlencode "start=$((now - 600))000000000" --data-urlencode "end=$((now + 60))000000000" \
        | grep '"values":\[\[' >/dev/null
}

# ─── The positive path ───────────────────────────────────────────────────────────────────────────
produce_payment PAY-91001 O-91001 49.99
check "the woken agent records a shipment prep for O-91001 (observed via kubectl exec psql, not model prose)" \
    poll 180 shipment_prep_is_a_valid_carrier O-91001
check "Tempo has a trace for service $SVC" poll 90 tempo_has_trace
check "Prometheus has a metric from $SVC" poll 90 prom_has_metric
check "Prometheus has the prepShipment tool-call counter" poll 90 prom_has_counter
check "Loki has the tool's own log line (observed, not model prose)" poll 90 loki_has_log "tool invoked: prepShipment"

# ─── Biting negative witness: disable the consumer by env, not by source edit ────────────────────
# A Deployment env var change (kubectl set env) rewrites the pod template, which triggers its own
# rollout; no separate restart command is needed. The row is the witness, not a point-in-time
# Prometheus query, for the same reason the compose example's verify.sh gives: the counter and the
# row are written in the same method call, so an absent row is already conclusive, and Prometheus
# keeps a terminated pod's last-exported sample live in its TSDB well past the rollout.
check "consumer disabled (WAKEUP_CONSUMER_ENABLED=false) and rolled out" bash -c '
    kubectl set env "deployment/$1" -n "$2" WAKEUP_CONSUMER_ENABLED=false &&
    kubectl rollout status "deploy/$1" -n "$2" --timeout=180s
' _ "$DEPLOY_NAME" "$APP_NS"
produce_payment PAY-91002 O-91002 199.99
sleep 8
check "negative witness: a disabled consumer leaves the event unconsumed (no shipment_prep row for O-91002)" \
    no_shipment_prep O-91002

check "consumer restored (WAKEUP_CONSUMER_ENABLED=true) and rolled out" bash -c '
    kubectl set env "deployment/$1" -n "$2" WAKEUP_CONSUMER_ENABLED=true &&
    kubectl rollout status "deploy/$1" -n "$2" --timeout=180s
' _ "$DEPLOY_NAME" "$APP_NS"
check "restored: the consumer that was disabled now records O-91002's shipment prep" \
    poll 180 shipment_prep_is_a_valid_carrier O-91002

echo "$PASSED/$TOTAL checks passed"

# ─── KEEP-gated teardown: this app only; the shared platform always stays up ─────────────────────
if [[ "${KEEP:-0}" == "1" ]]; then
    echo "KEEP=1: app left running; run \`kubectl delete -k ${APP_OVERLAY}\` to remove it (the shared platform stays up either way)"
else
    kubectl delete -k "$APP_OVERLAY" --ignore-not-found >/dev/null 2>&1 || true
fi

[[ "$PASSED" == "$TOTAL" ]]
