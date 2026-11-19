#!/usr/bin/env bash
# Captures a real Claude Code session consuming ch13's MCP server through the committed
# .mcp.json, sanitizes it, and writes it to transcript.md. This is the one deliverable that must
# be recorded live, not authored by hand: run it only after ./verify.sh KEEP=1 has left ch13's
# server up and healthy on http://localhost:8080. It never starts or stops the stack itself.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

if ! command -v claude >/dev/null 2>&1; then
    echo "capture-transcript.sh: 'claude' is not on PATH; install Claude Code first, then rerun" >&2
    exit 1
fi

if [[ ! -f .mcp.json ]]; then
    echo "capture-transcript.sh: .mcp.json not found in $(pwd)" >&2
    exit 1
fi

PROMPT='Using the apa-orders MCP server tools, look up order O-9001 and report its order status, payment status, and shipment carrier and tracking number.'

RAW_FILE=$(mktemp)
trap 'rm -f "$RAW_FILE"' EXIT

# --mcp-config pins the exact committed config instead of relying on cwd auto-discovery.
# A non-interactive (-p) run has no human present to approve a tool call, so the three read-only
# tools this chapter consumes are allow-listed by name (mcp__<server>__<tool>) rather than
# bypassing every permission check. Any tool outside this list stays denied, so the session can
# reach nothing beyond the read-only surface this chapter already documents.
if ! claude -p "$PROMPT" --mcp-config .mcp.json \
        --allowedTools mcp__apa-orders__orderStatus mcp__apa-orders__paymentStatus mcp__apa-orders__shipmentTracking \
        > "$RAW_FILE"; then
    echo "capture-transcript.sh: claude exited non-zero; raw output kept at $RAW_FILE for inspection" >&2
    trap - EXIT
    exit 1
fi

../../scripts/host_ids.py filter --repo ../.. < "$RAW_FILE" > transcript.md

echo "capture-transcript.sh: wrote the sanitized session to transcript.md"
