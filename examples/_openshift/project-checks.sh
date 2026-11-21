# shellcheck shell=bash
# project-checks.sh — the ch38 final-project data-mesh scenario
# (examples/38-final-project-data-mesh/verify.sh), translated to OpenShift:
# Routes instead of NodePorts, `oc exec` instead of a direct Kafka/Postgres
# connection, `kafka-console-producer.sh` instead of kcat (no NodePort or
# host tool stands in for the payments product's async output port here;
# the cluster's own Kafka broker pod runs the console producer in-cluster).
# Sourced by capture-evidence.sh, not run.
#
# Available from capture-evidence.sh: lib.sh helpers (step, ok, fail,
# wait_for), $OUT, $NS, $RELEASE, $PROJECT, https (curl with the ingress CA,
# never --insecure), in_pod <deploy> <cmd...> (oc exec into a Deployment's own
# container; not used for Postgres, a StatefulSet — see psql_super/psql_db
# below), route_url <route> [ns].
#
# Rules carried from verify.sh: every assertion is a captured side effect (a
# database row, a Kafka record, a registry response) and never the model's
# own prose; a secret (the Postgres passwords) is read into a shell variable
# and used over a remote `read`, never as a command-line argument (so it
# never appears in a `ps aux` listing on either side) and never printed.

MESH_DATABASES=(orders payments shipping shipping_track)
DB_OWNER_DEPLOYS=(orders payment-service shipping-track shipping-writer)
WRITER_DEPLOY="shipping-writer"
TOPIC="apa.payments.processed"
ORDER_1="O-38001"   # paid through the facade, consumed by the woken shipping writer
ORDER_2="O-38002"   # the mesh-hop witness: paid (by event) while the writer is scaled to 0

CONTRACTS_DIR="$REPO_ROOT/../contracts"
GROUP="apa38-$(date +%s)"   # unique per run: the cluster Apicurio persists artifacts across runs
ARTIFACT="apa.audit.agent-action.v2"
BASELINE_SCHEMA="$CONTRACTS_DIR/21-audit-agent-action.schema.json"
COMPATIBLE_SCHEMA="$CONTRACTS_DIR/32-audit-agent-action.v2-compatible.schema.json"
BREAKING_SCHEMA="$CONTRACTS_DIR/32-audit-agent-action.v2-breaking.schema.json"

# ─── Postgres: the app role never has CREATEDB; the superuser (reachable only
# because postgres.yaml sets POSTGRESQL_ADMIN_PASSWORD) does the reset. Both
# passwords are read once into a shell variable here and passed to the remote
# pod over `read`, never as an argv element. ─────────────────────────────────
PG_POD="$RELEASE-postgres-0"
PG_ADMIN_PASS="$(oc get secret "$RELEASE-postgres-app" -n "$NS" -o jsonpath='{.data.adminPassword}' 2>/dev/null | base64 -d)"
PG_APP_PASS="$(oc get secret "$RELEASE-postgres-app" -n "$NS" -o jsonpath='{.data.password}' 2>/dev/null | base64 -d)"

psql_super() { # psql_super <SQL>: the postgres superuser, over TCP to the
               # pod's own loopback (the sclorg image requires the password
               # here too once POSTGRESQL_ADMIN_PASSWORD is set).
    printf '%s\n' "$PG_ADMIN_PASS" | oc exec -i -n "$NS" "$PG_POD" -- \
        bash -c 'read -r pw; PGPASSWORD="$pw" exec psql -h 127.0.0.1 -U postgres -v ON_ERROR_STOP=1 -tAc "$1"' \
        _ "$1"
}

psql_db() { # psql_db <database> <SQL>: the apa role.
    printf '%s\n' "$PG_APP_PASS" | oc exec -i -n "$NS" "$PG_POD" -- \
        bash -c 'read -r pw; PGPASSWORD="$pw" exec psql -h 127.0.0.1 -U apa -d "$1" -v ON_ERROR_STOP=1 -tAc "$2"' \
        _ "$1" "$2"
}

valid_db_name() { [[ "$1" =~ ^[a-z_]+$ ]]; }   # a fixed set of literal constants; validated anyway

reset_database() { # reset_database <name>: drop and recreate, so every
                   # capture starts from an empty database, mirroring
                   # verify.sh's own fresh-run contract. WITH (FORCE)
                   # terminates any connection the running app pod still
                   # holds (Postgres 13+).
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

# Resetting a database severs the already-running app pod's connection and
# empties its schema; Flyway only migrates at JVM startup, so the four
# deployables that own a database are restarted here, exactly like verify.sh
# redeploying the mesh fresh after its own database reset.
restart_db_owning_deployments() {
    local d
    for d in "${DB_OWNER_DEPLOYS[@]}"; do
        oc rollout restart "deployment/$d" -n "$NS" >/dev/null || return 1
    done
    for d in "${DB_OWNER_DEPLOYS[@]}"; do
        oc rollout status "deployment/$d" -n "$NS" --timeout=300s >/dev/null || return 1
    done
}

# ─── MCP over Streamable HTTP, through each product's own Route (edge TLS,
# the cluster's ingress CA, never --insecure or a NodePort) ──────────────────
mcp_rpc() { # mcp_rpc <base_url> <path> <session-id> <json-body>
    local headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
    [[ -n "$3" ]] && headers+=(-H "Mcp-Session-Id: $3")
    https -sf "${headers[@]}" -d "$4" "$1$2"
}

mcp_initialize() { # mcp_initialize <base_url> <path>: prints "<session-id> <protocol-revision>"
    local base="$1" path="$2" header_file body_file status session revision
    header_file=$(mktemp); body_file=$(mktemp)
    trap 'rm -f "$header_file" "$body_file"' RETURN
    status=$(https -D "$header_file" -o "$body_file" -w '%{http_code}' \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"project-checks.sh","version":"1.0.0"}}}' \
        "$base$path")
    [[ "$status" == "200" ]] || return 1
    session=$(grep -i '^mcp-session-id:' "$header_file" | tr -d '\r' | cut -d' ' -f2)
    revision=$(jq -r '.result.protocolVersion' "$body_file")
    [[ -n "$session" ]] || return 1
    mcp_rpc "$base" "$path" "$session" '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
    printf '%s %s\n' "$session" "$revision"
}

orders_order_status_composed() {
    local base session revision f
    base="$(route_url orders)"
    read -r session revision < <(mcp_initialize "$base" "/mcp") || return 1
    f=$(mktemp); trap 'rm -f "$f"' RETURN
    mcp_rpc "$base" "/mcp" "$session" \
        '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"orderStatus","arguments":{"orderId":"O-9001"}}}' > "$f"
    jq -e '.result.content[0].text | fromjson
        | (.orderStatus == "SHIPPED") and (.paymentStatus == "COMPLETED")
          and (.carrier == "FASTLINE") and (.trackingNumber == "FL-778821") and (.shipmentStatus == "IN_TRANSIT")' "$f" >/dev/null
}

payments_take_payment_succeeds() {
    local base session revision f
    base="$(route_url payments-facade)"
    read -r session revision < <(mcp_initialize "$base" "/mcp") || return 1
    f=$(mktemp); trap 'rm -f "$f"' RETURN
    mcp_rpc "$base" "/mcp" "$session" \
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"takePayment\",\"arguments\":{\"orderId\":\"${ORDER_1}\",\"amount\":\"49.99\"}}}" \
        > "$f"
    jq -e --arg oid "$ORDER_1" '.result.content[0].text | fromjson | .orderId == $oid and .status == "COMPLETED"' "$f" >/dev/null
}

payments_db_row_completed() {
    [[ "$(psql_db payments "SELECT status FROM payments.payments WHERE order_id = '${ORDER_1}'")" == "COMPLETED" ]]
}

shipping_track_get_returns_seeded_tracking() {
    local base session revision f
    base="$(route_url shipping-track)"
    read -r session revision < <(mcp_initialize "$base" "/mcp/shipping") || return 1
    f=$(mktemp); trap 'rm -f "$f"' RETURN
    mcp_rpc "$base" "/mcp/shipping" "$session" \
        '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"shipping_track_get","arguments":{"orderId":"O-7001"}}}' > "$f"
    jq -e '.result.content[0].text | fromjson | .carrier == "FASTLINE" and .trackingNumber == "FL-440219"' "$f" >/dev/null
}

# ─── The payments-processed event: a Kafka broker pod's own console
# producer stands in for the payments product's async output port, over
# `oc exec` (never a NodePort: Kafka's only listener is `internal`, plain,
# reached in-cluster only; infra/kafka.yaml's auto.create.topics.enable
# creates the topic on first produce). No reused app in this mesh publishes
# this event itself (the same recon verify.sh records). ──────────────────────
kafka_pod() { oc get pod -n "$NS" -l "strimzi.io/cluster=$PROJECT" -o jsonpath='{.items[0].metadata.name}' 2>/dev/null; }

produce_payment() { # produce_payment <paymentId> <orderId> <amount>
    local pod; pod="$(kafka_pod)"
    [[ -n "$pod" ]] || return 1
    printf '%s:{"id":"%s","orderId":"%s","amount":%s,"status":"COMPLETED"}\n' "$2" "$1" "$2" "$3" \
        | oc exec -i -n "$NS" "$pod" -- /opt/kafka/bin/kafka-console-producer.sh \
            --bootstrap-server localhost:9092 --topic "$TOPIC" \
            --property parse.key=true --property key.separator=:
}

# ─── The shipping writer's durable effect, via `oc exec psql` against the
# shipping database (never the writer's own HTTP port: it has no tool
# surface and no Route). ─────────────────────────────────────────────────────
shipment_prep_carrier() { # shipment_prep_carrier <orderId>
    [[ "$1" =~ ^[A-Za-z0-9-]+$ ]] || { printf 'invalid order id: %s\n' "$1" >&2; return 1; }
    psql_db shipping "SELECT carrier FROM shipping.shipment_prep WHERE order_id = '$1'"
}
shipment_prep_is_a_valid_carrier() { # the live model decides the carrier; only the shape is
                                      # asserted, never its prose.
    local c; c=$(shipment_prep_carrier "$1")
    [[ "$c" == "FASTLINE" || "$c" == "AIRCO" ]]
}
no_shipment_prep() { [[ -z "$(shipment_prep_carrier "$1")" ]]; }

# ─── Governance: the live Apicurio BACKWARD gate, through its own Route ─────
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
    local apicurio content
    apicurio="$(route_url apicurio)"
    content=$(baseline_content_json_string "$BASELINE_SCHEMA")
    https -f -X POST "$apicurio/apis/registry/v3/groups/$GROUP/artifacts" -H 'Content-Type: application/json' \
        -d "{\"artifactId\":\"$ARTIFACT\",\"artifactType\":\"JSON\",\"firstVersion\":{\"content\":{\"content\":$content,\"contentType\":\"application/json\"}}}" \
        >/dev/null || return 1
    https -f -X POST "$apicurio/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT/rules" -H 'Content-Type: application/json' \
        -d '{"ruleType":"COMPATIBILITY","config":"BACKWARD"}' >/dev/null || return 1
    https -f "$apicurio/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT" | jq -e ".artifactId == \"$ARTIFACT\"" >/dev/null \
        && https -f "$apicurio/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT/rules/COMPATIBILITY" | jq -e '.config == "BACKWARD"' >/dev/null
}

VERSION_STATUS=""
VERSION_BODY_FILE=""
post_schema_version() { # post_schema_version <schema-file>: sets VERSION_STATUS and VERSION_BODY_FILE
    local apicurio content; apicurio="$(route_url apicurio)"
    content=$(python3 -I -c 'import json,sys; print(json.dumps(sys.stdin.read()))' <"$1")
    VERSION_BODY_FILE=$(mktemp)
    VERSION_STATUS=$(https -o "$VERSION_BODY_FILE" -w '%{http_code}' -X POST \
        "$apicurio/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT/versions" \
        -H 'Content-Type: application/json' \
        -d "{\"content\":{\"content\":$content,\"contentType\":\"application/json\"}}")
}

compatible_version_accepted() {
    post_schema_version "$COMPATIBLE_SCHEMA"
    local rc=1; [[ "$VERSION_STATUS" == 2?? ]] && rc=0
    rm -f "$VERSION_BODY_FILE"
    return "$rc"
}

breaking_version_rejected() {
    post_schema_version "$BREAKING_SCHEMA"
    local rc=1
    [[ "$VERSION_STATUS" == "400" ]] && jq -e '.name == "RuleViolationException"' "$VERSION_BODY_FILE" >/dev/null && rc=0
    rm -f "$VERSION_BODY_FILE"
    return "$rc"
}

project_checks() {
    local f="$OUT/04-project.txt"
    : > "$f"

    step "4a Per-product databases reset fresh, owned by apa, deployables restarted"
    databases_reset_and_owned_by_apa >>"$f" 2>&1 \
        || fail "database reset failed for one of ${MESH_DATABASES[*]} (see $f)"
    restart_db_owning_deployments >>"$f" 2>&1 \
        || fail "a database-owning Deployment did not roll out after the reset (see $f)"
    ok "orders, payments, shipping, shipping_track reset and owned by apa; ${DB_OWNER_DEPLOYS[*]} restarted"

    step "4b Host NodePort-equivalent readiness: Routes answer after the restart"
    # The workload PeerAuthentication making port 8080 PERMISSIVE (when the
    # mesh is on) needs a few seconds to reach each sidecar after a fresh
    # rollout; wait for each Route to answer before driving it.
    route_ready() { https -f -o /dev/null "$(route_url "$1")/q/health/ready"; }
    wait_for 90 "orders Route ready" route_ready orders
    wait_for 90 "payments-facade Route ready" route_ready payments-facade
    wait_for 90 "shipping-track Route ready" route_ready shipping-track

    step "4c ORDERS: orderStatus composes the seeded O-9001 across all three schemas"
    orders_order_status_composed >>"$f" 2>&1 || fail "orders: orderStatus did not compose O-9001 (see $f)"
    ok "orders Route: orderStatus(O-9001) composed"

    step "4d PAYMENTS: takePayment over the facade Route, payment-service writes the row"
    payments_take_payment_succeeds >>"$f" 2>&1 || fail "payments-facade: takePayment failed for $ORDER_1 (see $f)"
    payments_db_row_completed || fail "payment-service: no COMPLETED payments.payments row for $ORDER_1"
    ok "takePayment($ORDER_1) completed; payments.payments row COMPLETED"

    step "4e the payments-processed event reaches Kafka (stands in for payments' async output port)"
    produce_payment "PAY-38001" "$ORDER_1" 49.99 >>"$f" 2>&1 \
        || fail "kafka-console-producer.sh (oc exec) failed to produce to $TOPIC (see $f)"
    ok "produced to $TOPIC for $ORDER_1"

    step "4f SHIPPING: the woken writer records a shipment prep (oc exec psql, not model prose)"
    wait_for 420 "shipment_prep row for $ORDER_1" shipment_prep_is_a_valid_carrier "$ORDER_1"

    step "4g SHIPPING: shipping_track_get over its Route returns the seeded O-7001 tracking"
    shipping_track_get_returns_seeded_tracking >>"$f" 2>&1 \
        || fail "shipping-track Route: shipping_track_get did not return O-7001/FASTLINE/FL-440219 (see $f)"
    ok "shipping-track Route: shipping_track_get(O-7001) FASTLINE/FL-440219"

    step "4h Governance: the live Apicurio BACKWARD gate (reusing ch32's mechanism)"
    register_baseline_under_backward >>"$f" 2>&1 || fail "Apicurio: baseline registration under BACKWARD failed (see $f)"
    compatible_version_accepted || fail "Apicurio: a BACKWARD-compatible version (added optional field) was rejected"
    breaking_version_rejected || fail "Apicurio: a BACKWARD-incompatible version (new required field) was NOT rejected"
    ok "Apicurio Route: BACKWARD gate accepts the compatible version, rejects the breaking one"

    step "4i Biting mesh-hop witness: shipping-writer scaled to 0 leaves a paid order unshipped"
    oc scale "deployment/$WRITER_DEPLOY" -n "$NS" --replicas=0 >>"$f" 2>&1 \
        || fail "scale $WRITER_DEPLOY to 0 failed"
    oc rollout status "deployment/$WRITER_DEPLOY" -n "$NS" --timeout=120s >>"$f" 2>&1 \
        || fail "$WRITER_DEPLOY did not scale down"
    oc wait --for=delete pod -l "app.kubernetes.io/name=$WRITER_DEPLOY" -n "$NS" --timeout=120s >>"$f" 2>&1 \
        || fail "$WRITER_DEPLOY pod did not terminate"
    produce_payment "PAY-38002" "$ORDER_2" 149.99 >>"$f" 2>&1 \
        || fail "producing the mesh-hop witness event for $ORDER_2 failed"
    sleep 8
    no_shipment_prep "$ORDER_2" || fail "a shipment_prep row exists for $ORDER_2 while $WRITER_DEPLOY is scaled to 0"
    ok "paid but unshipped: no shipment_prep row for $ORDER_2 while the writer is down"

    step "4j Biting mesh-hop witness: restored, the missed event is consumed"
    oc scale "deployment/$WRITER_DEPLOY" -n "$NS" --replicas=1 >>"$f" 2>&1 \
        || fail "scale $WRITER_DEPLOY to 1 failed"
    oc rollout status "deployment/$WRITER_DEPLOY" -n "$NS" --timeout=180s >>"$f" 2>&1 \
        || fail "$WRITER_DEPLOY did not roll back out"
    wait_for 420 "shipment_prep row for $ORDER_2 after restore" shipment_prep_is_a_valid_carrier "$ORDER_2"
}
