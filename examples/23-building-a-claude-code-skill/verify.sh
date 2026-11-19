#!/usr/bin/env bash
# Starts ch13's package-api-as-mcp-tools server fresh, published to the host exactly as
# ../../skills/apa-order-ops/scripts/order-status.sh expects to find it, then checks the skill
# itself rather than a copy of it: SKILL.md's frontmatter lints clean, the skill's own script
# returns the live, seeded values for order O-9001, every operation
# references/order-operations.md documents actually appears in the server's tools/list, a
# plausible but undocumented operation name is provably absent (so that coverage check is not
# vacuous) and the script itself refuses to fabricate a result for it, and package-skill.sh
# produces a `.skill` bundle containing the packaged SKILL.md.
# KEEP=1 leaves the stack running afterwards.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

COMPOSE=(docker compose -f compose.yaml)
APP=http://localhost:8080
SKILL_NAME=apa-order-ops
SKILL_DIR=../../skills/$SKILL_NAME
ORDER_STATUS_SCRIPT="$SKILL_DIR/scripts/order-status.sh"
ORDER_OPS_REFERENCE="$SKILL_DIR/references/order-operations.md"
PACKAGE_SCRATCH=$(mktemp -d)
PACKAGE_OUT="$PACKAGE_SCRATCH/$SKILL_NAME.skill"
TOTAL=0
PASSED=0
TMP_FILES=()
MCP_SESSION_ID=""
PROTOCOL_REVISION=""
TOOLS_LIST_FILE=""
DOCUMENTED_NAMES=()

cleanup() {
    rm -f "${TMP_FILES[@]}" 2>/dev/null || true
    rm -rf "$PACKAGE_SCRATCH" 2>/dev/null || true
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

mcp_rpc() { # mcp_rpc <json-body>: POSTs to /mcp with the session header (if set), prints the body
    local headers=(-H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream')
    if [[ -n "$MCP_SESSION_ID" ]]; then
        headers+=(-H "Mcp-Session-Id: $MCP_SESSION_ID")
    fi
    curl -sf "${headers[@]}" -d "$1" "$APP/mcp"
}

mcp_initialize() { # sets MCP_SESSION_ID and PROTOCOL_REVISION as a side effect
    local header_file body_file status
    header_file=$(new_tmp_file)
    body_file=$(new_tmp_file)
    status=$(curl -s -D "$header_file" -o "$body_file" -w '%{http_code}' \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"verify-sh","version":"1.0.0"}}}' \
        "$APP/mcp")
    [[ "$status" == "200" ]] || return 1
    MCP_SESSION_ID=$(grep -i '^mcp-session-id:' "$header_file" | tr -d '\r' | cut -d' ' -f2)
    PROTOCOL_REVISION=$(jq -r '.result.protocolVersion' "$body_file")
    [[ -n "$MCP_SESSION_ID" ]] || return 1
    mcp_rpc '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
}

initialize_negotiates_the_expected_protocol_revision() {
    mcp_initialize
    [[ "$PROTOCOL_REVISION" == "2025-11-25" ]]
}

fetch_tools_list() {
    TOOLS_LIST_FILE=$(new_tmp_file)
    mcp_rpc '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' > "$TOOLS_LIST_FILE"
}

# b. Lint the skill: SKILL.md exists, carries YAML frontmatter with both `name` and
# `description`, and `name` equals the directory the skill lives in.
skill_md_front_matter_is_valid() {
    [[ -f "$SKILL_DIR/SKILL.md" ]] || return 1
    local fm name desc
    fm=$(awk '/^---[[:space:]]*$/{c++; next} c==1' "$SKILL_DIR/SKILL.md")
    [[ -n "$fm" ]] || return 1
    name=$(printf '%s\n' "$fm" | sed -n 's/^name:[[:space:]]*//p' | head -1 | tr -d '\r')
    desc=$(printf '%s\n' "$fm" | sed -n 's/^description:[[:space:]]*//p' | head -1 | tr -d '\r')
    [[ -n "$name" && -n "$desc" ]] || return 1
    [[ "$name" == "$SKILL_NAME" ]]
}

# c. Exercise the skill's own script against the live server: the values it prints must be
# ch13's seeded SHIPPED order, tracking FL-778821.
order_status_script_returns_the_seeded_values() {
    local out
    out=$(APA_ORDERS_MCP_URL="$APP/mcp" "$ORDER_STATUS_SCRIPT" O-9001)
    [[ "$out" == *SHIPPED* && "$out" == *FL-778821* ]]
}

# d. Name-coverage witness: every operation references/order-operations.md documents (its
# level-2 headings, each the exact tool name in backticks) must appear in the live tools/list.
# This is what proves the skill's documented surface matches the real server, not a copy of it
# that has drifted.
extract_documented_names() {
    mapfile -t DOCUMENTED_NAMES < <(grep -oE '^## `[A-Za-z]+`' "$ORDER_OPS_REFERENCE" | sed -E 's/^## `([A-Za-z]+)`$/\1/')
}

documented_names_all_appear_in_the_live_tools_list() {
    extract_documented_names
    [[ "${#DOCUMENTED_NAMES[@]}" -ge 1 ]] || return 1
    local n
    for n in "${DOCUMENTED_NAMES[@]}"; do
        jq -e --arg n "$n" '.result.tools[] | select(.name == $n)' "$TOOLS_LIST_FILE" >/dev/null || return 1
    done
}

# f. Biting negative witness, two halves. First: deleteOrder is a plausible name an author could
# mistakenly add to references/order-operations.md; it must be absent from the live tools/list,
# which is exactly what would make the membership check above fail the moment it were ever
# documented, proving that check is not vacuous. Second: order-status.sh itself, the thing a
# reader or an agent actually runs, must refuse to call deleteOrder rather than silently
# fabricating a result for it, which is the guard that matters once an agent is driving the
# skill directly instead of reading the reference file.
deleteorder_is_not_advertised_so_coverage_would_catch_it() {
    ! jq -e '.result.tools[] | select(.name == "deleteOrder")' "$TOOLS_LIST_FILE" >/dev/null
}

order_status_script_refuses_an_unadvertised_operation() {
    ! APA_ORDERS_MCP_URL="$APP/mcp" "$ORDER_STATUS_SCRIPT" O-9001 deleteOrder >/dev/null 2>&1
}

# e. Packaging: the second distribution channel. package-skill.sh must produce a `.skill`
# bundle (a zip archive) whose single top-level entry is the skill's own name, containing the
# packaged SKILL.md.
packaged_skill_bundle_contains_skill_md() {
    ./package-skill.sh "$PACKAGE_OUT" >/dev/null
    [[ -f "$PACKAGE_OUT" ]] || return 1
    unzip -l "$PACKAGE_OUT" | grep -q "$SKILL_NAME/SKILL.md"
}

# Fresh start: remove any containers and volumes left by an earlier run (KEEP=1, an aborted
# run), then create every container and anonymous volume anew.
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --build --force-recreate --renew-anon-volumes --wait

for c in apa-kafka apa-postgres apa-apicurio apa-keycloak apa-ollama apa-lgtm apa-23-package-api-as-mcp-tools; do
    check "$c is healthy" healthy "$c"
done

check "MCP initialize negotiates protocol revision 2025-11-25" initialize_negotiates_the_expected_protocol_revision
echo "--- advertised MCP protocol revision: ${PROTOCOL_REVISION:-unknown} ---"

check "tools/list is reachable" fetch_tools_list
check "SKILL.md has valid frontmatter and name equals the skill directory, apa-order-ops" \
    skill_md_front_matter_is_valid
check "scripts/order-status.sh returns the seeded SHIPPED order and FL-778821 tracking for O-9001" \
    order_status_script_returns_the_seeded_values
check "every operation references/order-operations.md documents appears in the live tools/list" \
    documented_names_all_appear_in_the_live_tools_list
check "deleteOrder is absent from tools/list, proving the coverage check above would catch it if ever documented" \
    deleteorder_is_not_advertised_so_coverage_would_catch_it
check "order-status.sh refuses to fabricate a result for deleteOrder, an operation the server never advertised" \
    order_status_script_refuses_an_unadvertised_operation
check "package-skill.sh produces a .skill bundle containing apa-order-ops/SKILL.md" \
    packaged_skill_bundle_contains_skill_md

echo "$PASSED/$TOTAL checks passed"
[[ "$PASSED" == "$TOTAL" ]]
