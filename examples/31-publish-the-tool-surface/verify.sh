#!/usr/bin/env bash
# Starts the stack with the publish-the-tool-surface app and checks that ONE Maven version
# reaches three artifacts identically: the MCP initialize handshake's serverInfo.version served
# over Streamable HTTP by the detached compose container, the SAME handshake field served over
# stdio by `docker run -i` against the IDENTICAL image tag, and the served /llms.txt. The tool
# surface (orderStatus, paymentStatus, shipmentTracking) is checked against the committed
# contracts/31-order-tools.mcp.json over both transports, and llms.txt is checked against the
# committed contracts/31-llms.txt. A biting negative witness, wired into the normal run, overrides
# the stdio serverInfo version by environment and confirms the three-way agreement breaks for that
# one invocation, then confirms a normal invocation afterwards is restored to agreement.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1)
export VERSION
IMAGE="publish-the-tool-surface:$VERSION"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8089
TOOLS_CONTRACT=../../contracts/31-order-tools.mcp.json
LLMS_CONTRACT=../../contracts/31-llms.txt
TOTAL=0
PASSED=0
TMP_FILES=()
MCP_SESSION_ID=""

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

# --- HTTP, driven live against the detached compose container -----------------------------------
mcp_rpc() { # mcp_rpc <json-body>: POSTs to /mcp with the session header (if set), prints the body
    local headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
    if [[ -n "$MCP_SESSION_ID" ]]; then
        headers+=(-H "Mcp-Session-Id: $MCP_SESSION_ID")
    fi
    curl -sf "${headers[@]}" -d "$1" "$APP/mcp"
}

mcp_initialize() { # sets MCP_SESSION_ID and HTTP_INIT_FILE as a side effect
    local header_file body_file status
    header_file=$(new_tmp_file)
    body_file=$(new_tmp_file)
    status=$(curl -s -D "$header_file" -o "$body_file" -w '%{http_code}' \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"verify-sh","version":"1.0.0"}}}' \
        "$APP/mcp")
    [[ "$status" == "200" ]] || return 1
    MCP_SESSION_ID=$(grep -i '^mcp-session-id:' "$header_file" | tr -d '\r' | cut -d' ' -f2)
    HTTP_INIT_FILE="$body_file"
    [[ -n "$MCP_SESSION_ID" ]] || return 1
    mcp_rpc '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
}

HTTP_INIT_FILE=""
HTTP_VERSION=""
http_initialize_reports_the_pom_version() {
    mcp_initialize
    HTTP_VERSION=$(jq -r '.result.serverInfo.version' "$HTTP_INIT_FILE")
    [[ "$HTTP_VERSION" == "$VERSION" ]]
}

HTTP_TOOLS_FILE=""
http_tools_list_matches_the_committed_contract() {
    HTTP_TOOLS_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' > "$HTTP_TOOLS_FILE"
    diff -q <(jq '.result.tools' "$HTTP_TOOLS_FILE") "$TOOLS_CONTRACT" >/dev/null
}

# --- stdio, the SAME image, one-shot over stdin/stdout, no network at all -----------------------
stdio_rpc_sequence() {
    printf '%s\n%s\n%s\n' \
        '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"verify-sh-stdio","version":"1.0.0"}}}' \
        '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
        '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
}

# Runs one stdio one-shot against $IMAGE, with any extra `docker run` arguments appended (used by
# the negative witness to override the server-info version by environment), and leaves only the
# JSON-RPC response lines in the given file. The quarkus-mcp-server-stdio extension already routes
# the banner and every log line to stderr while stdio is active, keeping stdout pure JSON-RPC; the
# jq filter here is a defensive second line, not the only one.
run_stdio() { # run_stdio <outfile> [extra docker run args...]
    local outfile="$1"; shift
    stdio_rpc_sequence | timeout 20 docker run -i --rm \
        -e QUARKUS_MCP_SERVER_STDIO_ENABLED=true "$@" \
        "$IMAGE" 2>/dev/null | jq -c 'select(.jsonrpc == "2.0")' > "$outfile" || true
}

STDIO_FILE=""
STDIO_VERSION=""
stdio_initialize_reports_the_pom_version() {
    STDIO_FILE=$(new_tmp_file)
    run_stdio "$STDIO_FILE"
    STDIO_VERSION=$(jq -rs 'map(select(.id == 1)) | .[0].result.serverInfo.version' "$STDIO_FILE")
    [[ "$STDIO_VERSION" == "$VERSION" ]]
}

stdio_tools_list_matches_the_committed_contract() {
    diff -q <(jq -s 'map(select(.id == 2)) | .[0].result.tools' "$STDIO_FILE") "$TOOLS_CONTRACT" >/dev/null
}

# --- the third artifact: /llms.txt ---------------------------------------------------------------
LLMS_FILE=""
llms_txt_is_served_as_text_plain() {
    LLMS_FILE=$(new_tmp_file)
    local content_type
    content_type=$(curl -sf -o "$LLMS_FILE" -w '%{content_type}' "$APP/llms.txt")
    [[ "$content_type" == text/plain* ]]
}

llms_txt_matches_the_committed_contract() {
    diff -q "$LLMS_FILE" "$LLMS_CONTRACT" >/dev/null
}

LLMS_VERSION=""
llms_txt_carries_the_pom_version() {
    LLMS_VERSION=$(sed -n 's/^Version: //p' "$LLMS_FILE")
    [[ "$LLMS_VERSION" == "$VERSION" ]]
}

# --- the point of the chapter: all three agree, by construction ---------------------------------
all_three_versions_agree() {
    [[ "$HTTP_VERSION" == "$VERSION" && "$STDIO_VERSION" == "$VERSION" && "$LLMS_VERSION" == "$VERSION" ]]
}

# --- biting negative witness, wired into the normal run, no source edit -------------------------
# A second stdio one-shot against the SAME image, with serverInfo's version overridden by
# environment (confirmed against the quarkus-mcp-server-core 2.0.4 jar's own config metadata:
# quarkus.mcp.server.server-info.version, env QUARKUS_MCP_SERVER_SERVER_INFO_VERSION), must
# disagree with the build's committed $VERSION, biting the exact equality the checks above assert.
drifted_stdio_version_bites_the_agreement_check() {
    local f drifted
    f=$(new_tmp_file)
    run_stdio "$f" -e QUARKUS_MCP_SERVER_SERVER_INFO_VERSION=9.9.9
    drifted=$(jq -rs 'map(select(.id == 1)) | .[0].result.serverInfo.version' "$f")
    # The asserted signal: the drifted invocation's own reported version is NOT the build's
    # version, so an agreement check run against it would fail, exactly as it should.
    [[ -n "$drifted" && "$drifted" != "$VERSION" ]]
}

normal_stdio_afterwards_is_restored_to_agreement() {
    local f v
    f=$(new_tmp_file)
    run_stdio "$f"
    v=$(jq -rs 'map(select(.id == 1)) | .[0].result.serverInfo.version' "$f")
    [[ "$v" == "$VERSION" ]]
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted run),
# then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

# Tag the exact image id now running detached as $IMAGE, so the stdio one-shot below runs the
# SAME image, not a second build from the same Containerfile that could in principle diverge.
docker tag "$(docker inspect -f '{{.Image}}' apa-publish-the-tool-surface)" "$IMAGE"
echo "--- built and tagged $IMAGE ---"

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-publish-the-tool-surface; do
    check "$c is healthy" healthy "$c"
done

check "HTTP initialize reports serverInfo.version == $VERSION" http_initialize_reports_the_pom_version
check "HTTP tools/list matches the committed contracts/31-order-tools.mcp.json" \
    http_tools_list_matches_the_committed_contract

check "stdio ($IMAGE, docker run -i) initialize reports serverInfo.version == $VERSION" \
    stdio_initialize_reports_the_pom_version
check "stdio tools/list matches the SAME committed contracts/31-order-tools.mcp.json" \
    stdio_tools_list_matches_the_committed_contract

check "/llms.txt is served as text/plain" llms_txt_is_served_as_text_plain
check "/llms.txt matches the committed contracts/31-llms.txt" llms_txt_matches_the_committed_contract
check "/llms.txt carries Version: $VERSION" llms_txt_carries_the_pom_version

check "HTTP, stdio and llms.txt all report the SAME version, by construction" all_three_versions_agree

check "biting: a stdio one-shot with QUARKUS_MCP_SERVER_SERVER_INFO_VERSION=9.9.9 disagrees with the build, breaking the agreement check" \
    drifted_stdio_version_bites_the_agreement_check
check "a normal stdio one-shot run immediately afterwards is restored to agreement with the build" \
    normal_stdio_afterwards_is_restored_to_agreement

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
