#!/usr/bin/env bash
# Scripts-only conformance harness (no app of its own). It reuses ch07's strict self-describing
# contract service and ch13's MCP tool server, both built unchanged from their own Containerfiles,
# and checks that each RUNNING service still matches its PUBLISHED contract, not just that it starts:
#   - OpenAPI conformance: the pinned Schemathesis image runs the committed REST contract against the
#     ch07 service, reached by its compose service DNS name on the apa network, and reports no failures.
#   - served vs committed OpenAPI: /q/openapi, normalized, is identical to the committed contract.
#   - MCP manifest conformance: the ch13 server's tools/list is identical to the committed manifest.
#   - biting negative witness: the SAME Schemathesis conformance against a committed DRIFTED OpenAPI,
#     and the SAME tools/list diff against a committed DRIFTED manifest, both FAIL, proving the checks
#     discriminate. This bite needs no source edits; it is part of the normal run.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
REPO_ROOT=$(cd ../.. && pwd)

CH07_SERVICE=strict-self-describing-contract        # compose service DNS name on the apa network
CH07_APP=http://localhost:8080                        # host-published ch07 port
MCP_APP=http://localhost:8088                         # host-published ch13 MCP port

SCHEMATHESIS_IMAGE=schemathesis/schemathesis:4.33.3
CHECKS=status_code_conformance,content_type_conformance,response_schema_conformance

CH07_CONTRACT=../../contracts/07-order-service.openapi.yaml
CH07_DRIFT_CONTRACT_IN_CTR=/contracts/27-order-service.drift.openapi.yaml
CH07_CONTRACT_IN_CTR=/contracts/07-order-service.openapi.yaml
MCP_CONTRACT=../../contracts/13-order-tools.mcp.json
MCP_DRIFT_CONTRACT=../../contracts/27-order-tools.drift.mcp.json

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

healthy() { [[ "$(docker inspect -f '{{.State.Health.Status}}' "$1")" == "healthy" ]]; }

new_tmp_file() { local f; f=$(mktemp); TMP_FILES+=("$f"); printf '%s' "$f"; }

# Prints canonical, sorted-key JSON for a JSON or YAML OpenAPI document, so a served document and a
# committed document can be diffed regardless of key order or JSON-vs-YAML formatting (ch07's approach).
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

# --- OpenAPI conformance (Schemathesis, reached by service DNS on the apa network) ---------------
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

# --- MCP manifest conformance (ch13 helpers, reused) ---------------------------------------------
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

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted run),
# then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm \
         apa-27-strict-self-describing-contract apa-27-package-api-as-mcp-tools; do
    check "$c is healthy" healthy "$c"
done

check "the running ch07 service conforms to the committed OpenAPI contract (Schemathesis, no failures)" \
    openapi_conformance_holds
check "the served /q/openapi matches the committed OpenAPI contract, normalized" \
    served_matches_committed_openapi

check "MCP tools/list is fetched from the running ch13 server" mcp_tools_list_fetched
echo "--- advertised MCP protocol revision: ${PROTOCOL_REVISION:-unknown} ---"
check "the served tools/list is identical to the committed contracts/13-order-tools.mcp.json" \
    served_tools_match_committed_manifest

# Biting negative witness: the same two conformance checks against committed drifted fixtures must FAIL.
check "the drifted OpenAPI contract is rejected: the same Schemathesis run reports conformance failures" \
    drifted_openapi_conformance_fails
check "the drifted MCP manifest is rejected: the served tools/list differs from the drifted manifest" \
    served_tools_differ_from_drifted_manifest

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
