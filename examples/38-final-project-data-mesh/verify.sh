#!/usr/bin/env bash
#
# Deploys the final project's focused multi-domain data mesh onto the shared examples/_k8s
# platform: three agent-facing data products, each owning its own database in the one CNPG
# cluster, assembled from reused chapter services (never re-forked). Orders (examples/13-package-
# api-as-mcp-tools) is a read-only composed status port. Payments (examples/15-reshape-grpc-
# behind-facade) is an MCP facade in front of an internal gRPC backend. Shipping is two reused
# services sharing one database in different schemas: the read port (examples/14-domain-split-
# progressive-discovery) and the event-woken writer (examples/17-event-triggered-agent-wakeup,
# reused as-is with one patched env var, see overlays/minikube/apps/38-data-mesh/kustomization.yaml).
#
# Drives one agent-driven scenario end to end across the mesh: an orders read, a payment taken
# over the facade, the resulting payments-processed event (stood in for with kcat, since no reused
# app publishes it; documented in contracts/38-data-mesh-catalog.md), the woken shipping writer's
# durable record, a shipping read, and the platform's own observability signals for the writer.
# Includes the live governance witness (an Apicurio BACKWARD compatibility gate, reusing ch32's
# mechanism against the cluster registry) and the biting mesh-hop negative witness (the shipping
# writer scaled to 0 leaves a paid order unshipped; scaling it back reads what it missed). Every
# assertion is a captured side effect: a database row, a Kafka record, a registry response, a
# platform signal. Never the model's own prose.
#
# Assumes the platform (examples/_k8s/bootstrap.sh) is already up; this script only gates on
# readiness. It also assumes the mesh's host NodePorts (examples/_k8s/lib/endpoints.sh: orders,
# payments, shipping-track) are published, which needs examples/_k8s/setup-profile.sh --replace
# once after this file lands (new NodePorts are fixed at profile creation).
#
# KEEP=1 leaves the mesh's Deployments and Services running afterwards; the shared platform (and
# ch37's own standalone deploy of the same shipping-writer image, if present) is never torn down
# by this script either way.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")" || exit 1

K8S_DIR="../_k8s"
# shellcheck source=../_k8s/lib/endpoints.sh
source "${K8S_DIR}/lib/endpoints.sh"

APP_NS="apa"
DATA_NS="$EP_DATA_NS"
OBS_NS="$EP_OBS_NS"
MESH_OVERLAY="${K8S_DIR}/overlays/minikube/apps/38-data-mesh"
WRITER_DEPLOY="event-triggered-agent-wakeup"
WRITER_SVC="event-triggered-agent-wakeup"
TOPIC="apa.payments.processed"

ORDERS_APP="http://127.0.0.1:${TP_ORDERS}"
PAYMENTS_APP="http://127.0.0.1:${TP_PAYMENTS}"
SHIPPING_APP="http://127.0.0.1:${TP_SHIPPING_TRACK}"

ORDER_1="O-38001"   # paid through the facade, consumed by the woken shipping writer
ORDER_2="O-38002"   # the mesh-hop witness: paid (by event) while the writer is scaled to 0

APICURIO="http://127.0.0.1:${TP_APICURIO}"
GROUP="apa38"
ARTIFACT="apa.audit.agent-action.v2"
BASELINE_SCHEMA=../../contracts/21-audit-agent-action.schema.json
COMPATIBLE_SCHEMA=../../contracts/32-audit-agent-action.v2-compatible.schema.json
BREAKING_SCHEMA=../../contracts/32-audit-agent-action.v2-breaking.schema.json

TOTAL=0
PASSED=0
TMP_FILES=()

require_tool() {
    local tool="$1" hint="$2"
    command -v "$tool" >/dev/null 2>&1 || { printf 'ERROR: %s is not in PATH.\n%s\n' "$tool" "$hint" >&2; exit 1; }
}
require_tool kubectl 'Install kubectl: https://kubernetes.io/docs/tasks/tools/'
require_tool kcat 'Install kcat (librdkafka-based Kafka CLI), for example: dnf install kcat'
require_tool jq 'Install jq'
require_tool python3 'Install python3'

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

new_tmp_file() { local f; f=$(mktemp); TMP_FILES+=("$f"); printf '%s' "$f"; }

cleanup() {
    rm -f "${TMP_FILES[@]}" 2>/dev/null || true
    if [[ "${KEEP:-0}" == "1" ]]; then
        echo "KEEP=1: mesh left running; run \`kubectl delete -k ${MESH_OVERLAY}\` to remove it (the shared platform stays up either way)"
    else
        kubectl delete -k "$MESH_OVERLAY" --ignore-not-found >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

# ─── Readiness gate: the shared platform, not this layer ────────────────────────────────────────
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
    kubectl get deployment apicurio -n "$DATA_NS" >/dev/null 2>&1 || return 1
}
if ! platform_ready; then
    printf 'ERROR: the examples/_k8s platform is not Ready.\n' >&2
    printf 'Run ../_k8s/setup-profile.sh then ../_k8s/bootstrap.sh, then re-run this script.\n' >&2
    exit 1
fi

# ─── Helpers: per-product databases, via kubectl exec psql against the CNPG primary ──────────────
PG_POD="$(kubectl get pods -n "$DATA_NS" -l cnpg.io/cluster=apa-postgres,cnpg.io/instanceRole=primary -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)"
PG_PASS="$(kubectl get secret -n "$DATA_NS" apa-postgres-app -o jsonpath='{.data.password}' 2>/dev/null | base64 -d)"

psql_super() { # psql_super <SQL>: the postgres superuser over the primary pod's own local socket
               # (no -h, no password: CNPG trust-authenticates a local connection as the postgres
               # OS user), used only for CREATE DATABASE and ownership grants, never for app data.
    kubectl exec -i -n "$DATA_NS" "$PG_POD" -- psql -U postgres -v ON_ERROR_STOP=1 -tAc "$1"
}

psql_db() { # psql_db <database> <SQL>: the apa role, password via the remote shell's own `read`
            # from stdin (never a command-line argument, so it never appears in a `ps aux`
            # listing on either side), SQL as a real argv element of the remote bash -c
            # invocation (never spliced into the script text).
    printf '%s\n' "$PG_PASS" | kubectl exec -i -n "$DATA_NS" "$PG_POD" -- \
        bash -c 'read -r pw; PGPASSWORD="$pw" exec psql -h 127.0.0.1 -U apa -d "$1" -v ON_ERROR_STOP=1 -tAc "$2"' \
        _ "$1" "$2"
}

valid_db_name() { [[ "$1" =~ ^[a-z_]+$ ]]; }  # a fixed set of literal constants; validated anyway

# One database per stateful deployable, so no service, even within one product, shares a mutable
# store with another: orders (ch13), payments (ch15 payment-service), shipping (ch17 writer, owns
# shipment_prep), shipping_track (ch14 read port, owns its own seeded read copy). Giving the two
# shipping deployables separate databases is what keeps their independent Flyway histories from
# colliding in a shared "shipping" schema.
MESH_DATABASES=(orders payments shipping shipping_track)

reset_database() { # reset_database <name>: drop and recreate, so every run starts from an empty
                   # database (the cluster Postgres persists across redeploys; this is the fresh-run
                   # contract's per-product analogue of the compose examples' `down -v`). WITH
                   # (FORCE) terminates any connection a prior run's pod still holds (Postgres 13+).
    local db="$1"
    valid_db_name "$db" || { printf 'invalid database name: %s\n' "$db" >&2; return 1; }
    psql_super "DROP DATABASE IF EXISTS ${db} WITH (FORCE)" >/dev/null || return 1
    psql_super "CREATE DATABASE ${db}" >/dev/null || return 1
    psql_super "GRANT ALL ON DATABASE ${db} TO apa" >/dev/null || return 1
    psql_super "ALTER DATABASE ${db} OWNER TO apa" >/dev/null || return 1
}

databases_reset_and_owned_by_apa() {
    local db owner
    for db in "${MESH_DATABASES[@]}"; do
        reset_database "$db" || return 1
        owner="$(psql_super "SELECT pg_catalog.pg_get_userbyid(datdba) FROM pg_database WHERE datname='${db}'")"
        [[ "$owner" == "apa" ]] || return 1
    done
}

# ─── Helpers: MCP over Streamable HTTP, against the mesh's own NodePorts ─────────────────────────
mcp_rpc() { # mcp_rpc <base_url> <path> <session-id> <json-body>
    local headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
    [[ -n "$3" ]] && headers+=(-H "Mcp-Session-Id: $3")
    curl -sf "${headers[@]}" -d "$4" "$1$2"
}

mcp_initialize() { # mcp_initialize <base_url> <path>: prints "<session-id> <protocol-revision>"
    local base="$1" path="$2" header_file body_file status session revision
    header_file=$(new_tmp_file); body_file=$(new_tmp_file)
    status=$(curl -s -D "$header_file" -o "$body_file" -w '%{http_code}' \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"verify-sh","version":"1.0.0"}}}' \
        "$base$path")
    [[ "$status" == "200" ]] || return 1
    session=$(grep -i '^mcp-session-id:' "$header_file" | tr -d '\r' | cut -d' ' -f2)
    revision=$(jq -r '.result.protocolVersion' "$body_file")
    [[ -n "$session" ]] || return 1
    mcp_rpc "$base" "$path" "$session" '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
    printf '%s %s\n' "$session" "$revision"
}

orders_order_status_composed() {
    local session revision f
    read -r session revision < <(mcp_initialize "$ORDERS_APP" "/mcp") || return 1
    f=$(new_tmp_file)
    mcp_rpc "$ORDERS_APP" "/mcp" "$session" \
        '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"orderStatus","arguments":{"orderId":"O-9001"}}}' > "$f"
    jq -e '.result.content[0].text | fromjson
        | (.orderStatus == "SHIPPED") and (.paymentStatus == "COMPLETED")
          and (.carrier == "FASTLINE") and (.trackingNumber == "FL-778821") and (.shipmentStatus == "IN_TRANSIT")' "$f" >/dev/null
}

TAKE_PAYMENT_FILE=""
payments_take_payment_succeeds() {
    local session revision
    read -r session revision < <(mcp_initialize "$PAYMENTS_APP" "/mcp") || return 1
    TAKE_PAYMENT_FILE=$(new_tmp_file)
    mcp_rpc "$PAYMENTS_APP" "/mcp" "$session" \
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"takePayment\",\"arguments\":{\"orderId\":\"${ORDER_1}\",\"amount\":\"49.99\"}}}" \
        > "$TAKE_PAYMENT_FILE"
    jq -e --arg oid "$ORDER_1" '.result.content[0].text | fromjson | .orderId == $oid and .status == "COMPLETED"' \
        "$TAKE_PAYMENT_FILE" >/dev/null
}

payments_db_row_completed() {
    [[ "$(psql_db payments "SELECT status FROM payments.payments WHERE order_id = '${ORDER_1}'")" == "COMPLETED" ]]
}

shipping_track_get_returns_seeded_tracking() {
    local session revision f
    read -r session revision < <(mcp_initialize "$SHIPPING_APP" "/mcp/shipping") || return 1
    f=$(new_tmp_file)
    mcp_rpc "$SHIPPING_APP" "/mcp/shipping" "$session" \
        '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"shipping_track_get","arguments":{"orderId":"O-7001"}}}' > "$f"
    jq -e '.result.content[0].text | fromjson | .carrier == "FASTLINE" and .trackingNumber == "FL-440219"' "$f" >/dev/null
}

# ─── Helper: the payments-processed event, over the platform's Kafka NodePort ────────────────────
# No reused app publishes this event (recon: app 15's payment-service writes payments.payments but
# never produces to Kafka); kcat stands in for the payments product's async output port, documented
# as such in contracts/38-data-mesh-catalog.md.
produce_payment() { # produce_payment <paymentId> <orderId> <amount>
    printf '%s:{"id":"%s","orderId":"%s","amount":%s,"status":"COMPLETED"}\n' "$2" "$1" "$2" "$3" \
        | kcat -P -b "127.0.0.1:${TP_KAFKA}" -t "$TOPIC" -K:
}

# ─── Helpers: the shipping writer's durable effect, via kubectl exec psql against the shipping db ─
shipment_prep_carrier() { # shipment_prep_carrier <orderId>
    [[ "$1" =~ ^[A-Za-z0-9-]+$ ]] || { printf 'invalid order id: %s\n' "$1" >&2; return 1; }
    psql_db shipping "SELECT carrier FROM shipping.shipment_prep WHERE order_id = '$1'"
}
shipment_prep_is_a_valid_carrier() { # the live model decides which carrier; only the shape is asserted, never its prose
    local c; c=$(shipment_prep_carrier "$1")
    [[ "$c" == "FASTLINE" || "$c" == "AIRCO" ]]
}
no_shipment_prep() { [[ -z "$(shipment_prep_carrier "$1")" ]]; }

# ─── Helpers: traces, metrics and logs, over the platform's own Tempo/Prometheus/Loki NodePorts ──
tempo_has_trace() {
    curl -sf -G "http://127.0.0.1:${TP_TEMPO}/api/search" --data-urlencode "tags=service.name=$WRITER_SVC" | grep '"traceID"' >/dev/null
}
prom_has_metric() {
    curl -sf -G "http://127.0.0.1:${TP_PROMETHEUS}/api/v1/query" --data-urlencode "query=target_info{service_name=\"$WRITER_SVC\"}" \
        | grep '"result":\[{' >/dev/null
}
loki_has_log() { # loki_has_log <search string>
    local now; now=$(date +%s)
    curl -sf -G "http://127.0.0.1:${TP_LOKI}/loki/api/v1/query_range" --data-urlencode "query={service_name=\"$WRITER_SVC\"} |= \"$1\"" \
        --data-urlencode "start=$((now - 600))000000000" --data-urlencode "end=$((now + 60))000000000" \
        | grep '"values":\[\[' >/dev/null
}

# ─── Helpers: the live governance witness (Apicurio BACKWARD gate, ch32's mechanism, reused) ──────
baseline_content_json_string() { # prints the baseline file's content as a JSON string, $schema
                                  # forced to a dialect the compatibility checker can resolve
                                  # (Apicurio Registry 3.3.4's JSON Schema checker returns HTTP 422
                                  # for 2020-12; draft-07 is the newest dialect it resolves).
    python3 -I -c '
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    schema = json.load(f)
schema["$schema"] = "http://json-schema.org/draft-07/schema#"
print(json.dumps(json.dumps(schema)))
' "$1"
}

register_baseline_under_backward() {
    local content
    content=$(baseline_content_json_string "$BASELINE_SCHEMA")
    curl -sf -X POST "$APICURIO/apis/registry/v3/groups/$GROUP/artifacts" -H 'Content-Type: application/json' \
        -d "{\"artifactId\":\"$ARTIFACT\",\"artifactType\":\"JSON\",\"firstVersion\":{\"content\":{\"content\":$content,\"contentType\":\"application/json\"}}}" \
        >/dev/null || return 1
    curl -sf -X POST "$APICURIO/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT/rules" -H 'Content-Type: application/json' \
        -d '{"ruleType":"COMPATIBILITY","config":"BACKWARD"}' >/dev/null || return 1
    curl -sf "$APICURIO/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT" | jq -e ".artifactId == \"$ARTIFACT\"" >/dev/null \
        && curl -sf "$APICURIO/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT/rules/COMPATIBILITY" | jq -e '.config == "BACKWARD"' >/dev/null
}

VERSION_STATUS=""
VERSION_BODY_FILE=""
post_schema_version() { # post_schema_version <schema-file>: sets VERSION_STATUS and VERSION_BODY_FILE
    local content; content=$(python3 -I -c 'import json,sys; print(json.dumps(sys.stdin.read()))' <"$1")
    VERSION_BODY_FILE=$(new_tmp_file)
    VERSION_STATUS=$(curl -s -o "$VERSION_BODY_FILE" -w '%{http_code}' -X POST \
        "$APICURIO/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT/versions" \
        -H 'Content-Type: application/json' \
        -d "{\"content\":{\"content\":$content,\"contentType\":\"application/json\"}}")
}

compatible_version_accepted() {
    post_schema_version "$COMPATIBLE_SCHEMA"
    [[ "$VERSION_STATUS" == 2?? ]]
}

breaking_version_rejected() {
    post_schema_version "$BREAKING_SCHEMA"
    [[ "$VERSION_STATUS" == "400" ]] && jq -e '.name == "RuleViolationException"' "$VERSION_BODY_FILE" >/dev/null
}

# ─── Per-deployable databases: reset fresh (DROP WITH FORCE + CREATE) before the mesh starts, so
# each service migrates a clean database and no run inherits the last run's rows. ─────────────────
check "every mesh database is reset fresh and owned by apa (orders, payments, shipping, shipping_track)" \
    databases_reset_and_owned_by_apa

# ─── Fresh deploy: this layer only, never the shared platform ───────────────────────────────────
check "the data mesh applied fresh across all 5 deployments in the apa namespace" bash -c '
    kubectl delete -k "$1" --ignore-not-found &&
    kubectl apply -k "$1"
' _ "$MESH_OVERLAY"

check "orders rolled out Ready"          kubectl rollout status deploy/orders -n "$APP_NS" --timeout=300s
check "payments-facade rolled out Ready" kubectl rollout status deploy/payments-facade -n "$APP_NS" --timeout=300s
check "payment-service rolled out Ready" kubectl rollout status deploy/payment-service -n "$APP_NS" --timeout=300s
check "shipping-track rolled out Ready"  kubectl rollout status deploy/shipping-track -n "$APP_NS" --timeout=300s
check "shipping-writer rolled out Ready, owning the shipping database" \
    kubectl rollout status "deploy/$WRITER_DEPLOY" -n "$APP_NS" --timeout=300s

# ─── The positive path: orders read, payment taken, event produced, shipment written, read back ──
check "ORDERS: orderStatus composes the seeded O-9001 from all three schemas in the orders database" \
    orders_order_status_composed

check "PAYMENTS: takePayment over the facade NodePort completes for ${ORDER_1}" \
    payments_take_payment_succeeds
check "PAYMENTS: payment-service writes a COMPLETED payments.payments row for ${ORDER_1} in the payments database" \
    payments_db_row_completed

check "the payments-processed event for ${ORDER_1} reaches the Kafka NodePort (stands in for payments' async output port)" \
    produce_payment "PAY-38001" "$ORDER_1" 49.99

check "SHIPPING: the woken writer records a shipment prep for ${ORDER_1} with a valid carrier (kubectl exec psql, not model prose)" \
    poll 420 shipment_prep_is_a_valid_carrier "$ORDER_1"
check "Tempo has a trace for service $WRITER_SVC" poll 90 tempo_has_trace
check "Prometheus has a metric from $WRITER_SVC" poll 90 prom_has_metric
check "Loki has the shipping writer's own log line" poll 90 loki_has_log "tool invoked: prepShipment"

check "SHIPPING: shipping_track_get over its NodePort returns the seeded O-7001 tracking (FASTLINE/FL-440219)" \
    shipping_track_get_returns_seeded_tracking

# ─── Governance: the live biting witness, reusing ch32's Apicurio BACKWARD gate against the ──────
# cluster registry's own NodePort. ────────────────────────────────────────────────────────────────
check "governance: a schema is registered in the cluster Apicurio under the BACKWARD compatibility rule" \
    register_baseline_under_backward
check "governance: a BACKWARD-compatible next version (an added optional field) is accepted" \
    compatible_version_accepted
check "governance: a BACKWARD-incompatible version (a new required field) is rejected" \
    breaking_version_rejected

# ─── Biting negative witness: the mesh-hop cut (scale the shipping writer to 0, never a source
# edit), isolating the break to one hop: paid but unshipped. ─────────────────────────────────────
check "mesh-hop witness: shipping writer scaled to 0 and rolled out" bash -c '
    kubectl scale "deployment/$1" -n "$2" --replicas=0 &&
    kubectl rollout status "deploy/$1" -n "$2" --timeout=120s
' _ "$WRITER_DEPLOY" "$APP_NS"

produce_payment "PAY-38002" "$ORDER_2" 149.99 >/dev/null
sleep 8
check "mesh-hop witness: a paid order (${ORDER_2}) with the writer down leaves no shipment_prep row (paid but unshipped)" \
    no_shipment_prep "$ORDER_2"

check "mesh-hop witness: shipping writer restored to 1 replica and rolled out" bash -c '
    kubectl scale "deployment/$1" -n "$2" --replicas=1 &&
    kubectl rollout status "deploy/$1" -n "$2" --timeout=180s
' _ "$WRITER_DEPLOY" "$APP_NS"
check "mesh-hop witness: restored, the missed event is now consumed and the row appears for ${ORDER_2}" \
    poll 420 shipment_prep_is_a_valid_carrier "$ORDER_2"

echo "$PASSED/$TOTAL checks passed"

[[ "$PASSED" == "$TOTAL" ]]
