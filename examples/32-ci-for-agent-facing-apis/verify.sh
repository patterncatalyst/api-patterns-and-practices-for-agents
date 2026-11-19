#!/usr/bin/env bash
# Scripts-only CI harness (no app of its own), two gates:
#   (a) contract drift: the SAME Schemathesis + normalized OpenAPI diff + tools/list diff ch27 built,
#       reused verbatim here against the same two reused services (ch07, ch13), including the same
#       biting negative witness against committed drifted fixtures.
#   (b) schema compatibility: a JSON Schema registered in Apicurio under the COMPATIBILITY rule
#       BACKWARD accepts a compatible next version and rejects an incompatible one. The registry
#       itself is the gate; this harness only asserts what it returns.
# Gate (b) note: Apicurio Registry 3.3.4's JSON Schema compatibility checker could not determine the
# schema version (HTTP 422, UnprocessableSchemaException) for the 2020-12 dialect that the committed
# baseline, contracts/21-audit-agent-action.schema.json, declares; no later version posted against a
# 2020-12 artifact ever reaches the compatibility algorithm at all, compatible or not. draft-07 is the
# newest dialect the checker resolves (draft-04 and draft-06 also work; 2019-09 does not). This harness
# registers the committed baseline's content unchanged except for that one field; see README.md.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
REPO_ROOT=$(cd ../.. && pwd)

CH07_SERVICE=strict-self-describing-contract        # compose service DNS name on the apa network
CH07_APP=http://localhost:8080                        # host-published ch07 port
MCP_APP=http://localhost:8088                         # host-published ch13 MCP port
APICURIO=http://localhost:8081

SCHEMATHESIS_IMAGE=schemathesis/schemathesis:4.33.3
CHECKS=status_code_conformance,content_type_conformance,response_schema_conformance

CH07_CONTRACT=../../contracts/07-order-service.openapi.yaml
CH07_DRIFT_CONTRACT_IN_CTR=/contracts/27-order-service.drift.openapi.yaml
CH07_CONTRACT_IN_CTR=/contracts/07-order-service.openapi.yaml
MCP_CONTRACT=../../contracts/13-order-tools.mcp.json
MCP_DRIFT_CONTRACT=../../contracts/27-order-tools.drift.mcp.json

GROUP=apa32
ARTIFACT=apa.audit.agent-action.v2
BASELINE_SCHEMA=../../contracts/21-audit-agent-action.schema.json
COMPATIBLE_SCHEMA=../../contracts/32-audit-agent-action.v2-compatible.schema.json
BREAKING_SCHEMA=../../contracts/32-audit-agent-action.v2-breaking.schema.json

TOTAL=0
PASSED=0
TMP_FILES=()
MCP_SESSION_ID=""
PROTOCOL_REVISION=""

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

healthy() { [[ "$(docker inspect -f '{{.State.Health.Status}}' "$1")" == "healthy" ]]; }

new_tmp_file() { local f; f=$(mktemp); TMP_FILES+=("$f"); printf '%s' "$f"; }

# Prints canonical, sorted-key JSON for a JSON or YAML OpenAPI document, so a served document and a
# committed document can be diffed regardless of key order or JSON-vs-YAML formatting (ch27's approach).
normalize() {
    python3 - "$1" <<'PY'
import json
import sys

import yaml

with open(sys.argv[1], "r", encoding="utf-8") as f:
    text = f.read()
try:
    data = json.loads(text)
except json.JSONDecodeError:
    data = yaml.safe_load(text)
print(json.dumps(data, sort_keys=True, indent=2))
PY
}

# --- Gate (a): OpenAPI conformance (Schemathesis, reached by service DNS on the apa network) ------
SCHEMATHESIS_OUT=""
SCHEMATHESIS_RC=0
schemathesis_run() { # $1 = schema location inside the mounted /contracts
    SCHEMATHESIS_OUT=$(new_tmp_file)
    docker run --rm --network apa -v "$REPO_ROOT/contracts:/contracts:ro" \
        "$SCHEMATHESIS_IMAGE" run "$1" \
        -u "http://$CH07_SERVICE:8080" \
        -c "$CHECKS" >"$SCHEMATHESIS_OUT" 2>&1
    SCHEMATHESIS_RC=$?
}

openapi_conformance_holds() {
    schemathesis_run "$CH07_CONTRACT_IN_CTR"
    # The asserted signal: the conformance run reports no failures (Schemathesis exits zero).
    [[ "$SCHEMATHESIS_RC" -eq 0 ]]
}

drifted_openapi_conformance_fails() {
    schemathesis_run "$CH07_DRIFT_CONTRACT_IN_CTR"
    # The asserted signal: the same conformance run against the drifted contract reports failures.
    [[ "$SCHEMATHESIS_RC" -ne 0 ]]
}

# --- served vs committed OpenAPI -----------------------------------------------------------------
served_matches_committed_openapi() {
    local served_norm committed_norm fetched
    served_norm=$(new_tmp_file)
    committed_norm=$(new_tmp_file)
    fetched=$(new_tmp_file)
    curl -sf -H 'Accept: application/json' "$CH07_APP/q/openapi" -o "$fetched" || return 1
    normalize "$fetched" > "$served_norm"
    normalize "$CH07_CONTRACT" > "$committed_norm"
    diff -q "$served_norm" "$committed_norm" >/dev/null
}

# --- MCP manifest conformance (ch13 helpers, reused) -----------------------------------------------
mcp_rpc() { # mcp_rpc <json-body>: POSTs to /mcp with the session header (if set), prints the body
    local headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
    if [[ -n "$MCP_SESSION_ID" ]]; then
        headers+=(-H "Mcp-Session-Id: $MCP_SESSION_ID")
    fi
    curl -sf "${headers[@]}" -d "$1" "$MCP_APP/mcp"
}

mcp_initialize() { # sets MCP_SESSION_ID and PROTOCOL_REVISION as a side effect
    local header_file body_file status
    header_file=$(new_tmp_file)
    body_file=$(new_tmp_file)
    status=$(curl -s -D "$header_file" -o "$body_file" -w '%{http_code}' \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"verify-sh","version":"1.0.0"}}}' \
        "$MCP_APP/mcp")
    [[ "$status" == "200" ]] || return 1
    MCP_SESSION_ID=$(grep -i '^mcp-session-id:' "$header_file" | tr -d '\r' | cut -d' ' -f2)
    PROTOCOL_REVISION=$(jq -r '.result.protocolVersion' "$body_file")
    [[ -n "$MCP_SESSION_ID" ]] || return 1
    mcp_rpc '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
}

TOOLS_LIST_FILE=""
mcp_tools_list_fetched() {
    mcp_initialize || return 1
    TOOLS_LIST_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' > "$TOOLS_LIST_FILE"
    jq -e '.result.tools | length >= 1' "$TOOLS_LIST_FILE" >/dev/null
}

served_tools_match_committed_manifest() {
    diff -q <(jq '.result.tools' "$TOOLS_LIST_FILE") "$MCP_CONTRACT" >/dev/null
}

served_tools_differ_from_drifted_manifest() {
    ! diff -q <(jq '.result.tools' "$TOOLS_LIST_FILE") "$MCP_DRIFT_CONTRACT" >/dev/null 2>&1
}

# --- Gate (b): Apicurio Registry COMPATIBILITY rule, BACKWARD (REST API v3) ------------------------
baseline_content_json_string() { # prints the baseline file's content, as a JSON string, $schema forced
                                  # to a dialect the compatibility checker can resolve (see the file
                                  # header above); the registered content differs from the committed
                                  # file only in that one field.
    python3 -I -c '
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    schema = json.load(f)
schema["$schema"] = "http://json-schema.org/draft-07/schema#"
print(json.dumps(json.dumps(schema)))
' "$1"
}

register_baseline() {
    local content; content=$(baseline_content_json_string "$BASELINE_SCHEMA")
    curl -sf -X POST "$APICURIO/apis/registry/v3/groups/$GROUP/artifacts" -H 'Content-Type: application/json' \
        -d "{\"artifactId\":\"$ARTIFACT\",\"artifactType\":\"JSON\",\"firstVersion\":{\"content\":{\"content\":$content,\"contentType\":\"application/json\"}}}" \
        >/dev/null
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
    # The asserted signal: the registry accepts the new version (2xx), the schema that only adds an
    # optional field a reader built against the baseline never needed.
    [[ "$VERSION_STATUS" == 2?? ]]
}

breaking_version_rejected() {
    post_schema_version "$BREAKING_SCHEMA"
    # The asserted signal: the registry blocks the write with its own RuleViolationException (HTTP
    # 400, confirmed live against Apicurio 3.3.4; not a generic 4xx), the schema that adds a required
    # field with no default, which no record written before it carries.
    [[ "$VERSION_STATUS" == "400" ]] && jq -e '.name == "RuleViolationException"' "$VERSION_BODY_FILE" >/dev/null
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted run),
# then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm \
         apa-32-strict-self-describing-contract apa-32-package-api-as-mcp-tools apa-32-postgres-contract; do
    check "$c is healthy" healthy "$c"
done

echo "Gate (a): contract drift"
check "the running ch07 service conforms to the committed OpenAPI contract (Schemathesis, no failures)" \
    openapi_conformance_holds
check "the served /q/openapi matches the committed OpenAPI contract, normalized" \
    served_matches_committed_openapi
check "MCP tools/list is fetched from the running ch13 server" mcp_tools_list_fetched
echo "--- advertised MCP protocol revision: ${PROTOCOL_REVISION:-unknown} ---"
check "the served tools/list is identical to the committed contracts/13-order-tools.mcp.json" \
    served_tools_match_committed_manifest
check "the drifted OpenAPI contract is rejected: the same Schemathesis run reports conformance failures" \
    drifted_openapi_conformance_fails
check "the drifted MCP manifest is rejected: the served tools/list differs from the drifted manifest" \
    served_tools_differ_from_drifted_manifest

echo "Gate (b): schema compatibility"
precondition "the baseline audit-event schema registers in Apicurio group $GROUP" register_baseline
precondition "the COMPATIBILITY rule is set to BACKWARD" set_backward_compatibility
check "the artifact $ARTIFACT exists in group $GROUP" artifact_registered
check "the artifact's compatibility rule reads back as BACKWARD" compatibility_is_backward
check "a BACKWARD-compatible next version (adds an optional field) is accepted" compatible_version_accepted
check "a BACKWARD-incompatible next version (adds a required field) is rejected by the registry itself" \
    breaking_version_rejected

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
