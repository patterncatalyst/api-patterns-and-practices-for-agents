---
title: "Consuming an MCP Server from Claude Code"
order: 22
part: "Consume"
description: "A human points Claude Code at a packaged MCP tool server, discovers its tool, resource and prompt surface, and drives it, the same surface a scripted client exercises deterministically."
duration: "20 minutes"
---

## Learning objectives

- Register an MCP server for Claude Code with a committed `.mcp.json`, pointing a client at a server rather than wrapping or forking it.
- Reproduce an interactive session's lifecycle (`initialize`, `tools/list`, `resources/list`, `prompts/list`, `tools/call`) as a deterministic, scripted check.
- Treat `tools/list` as the actual boundary of what a consumer can drive, not a convention a client happens to honor.
- Recognize an unpinned server URL and an assumed tool surface as the ways a consumer silently drifts from what a server actually advertises.

## Why this matters

A packaged MCP tool server is only useful once something consumes it, and the consumer that matters most to a developer working in a terminal is Claude Code itself: a human types a question, Claude Code negotiates the MCP handshake, lists what the server advertises, and calls whichever tool answers the question. That session is valuable and also impossible to grade: a transcript records one particular conversation, with no guarantee the next run asks the same question or gets the same answer. Something has to drive the same client lifecycle a human session drives, every time, against whatever the server currently advertises, so a change to the server's tool surface is caught the moment it stops matching what a consumer expects.

{% include excalidraw.html file="22-mcp-client-lifecycle" alt="Claude Code picks up the committed .mcp.json and negotiates initialize, tools/list, resources/list and prompts/list against a packaged MCP tool server; a scripted JSON-RPC client drives the identical lifecycle as a deterministic, repeatable check, and a tool absent from tools/list is rejected rather than driven" caption="Figure 22.1: The same client lifecycle, driven by a human session and by a deterministic script" %}

## Pattern: Consuming an MCP Server from Claude Code

### The problem

An interactive Claude Code session proves a server works for one question, asked once, by one person, at one moment. It proves nothing about whether the server's tool surface still matches what a consumer expects after the next change, and a transcript has no pass or fail: reading it back tells a reader what happened, not whether what happened was correct. A team that only ever consumes a packaged MCP server by hand has no repeatable way to catch a renamed tool, a dropped resource, or a response shape that quietly changed.

### The solution

**A committed MCP client configuration registers the server for an interactive session, while a deterministic scripted client drives the identical connect-discover-call lifecycle as a repeatable check, so a human session and an automated one are provably exercising the same surface.**

### How an MCP client models it

The committed `.mcp.json` is the real Claude Code configuration, registering a packaged order-to-delivery MCP tool server under a name and a Streamable HTTP URL:

```json
{
  "mcpServers": {
    "apa-orders": {
      "type": "http",
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

A Claude Code session started in the same directory picks this up automatically and can call whatever the server advertises, no further wiring needed.

`mcp-client.sh` is the deterministic stand-in for that session: a standalone bash, curl and jq script performing the exact lifecycle a client runs the moment it connects, `initialize`, the `notifications/initialized` handshake, `tools/list`, `resources/list`, `prompts/list`, then a `tools/call` for each advertised tool against a seeded order, a `resources/read` of the playbook resource, and a `prompts/get` of the summary prompt:

```bash
mcp_initialize() { # sets MCP_SESSION_ID and PROTOCOL_REVISION as a side effect
    status=$(curl -s -D "$header_file" -o "$body_file" -w '%{http_code}' \
        -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25", ...}}' \
        "$MCP_URL")
    MCP_SESSION_ID=$(grep -i '^mcp-session-id:' "$header_file" | tr -d '\r' | cut -d' ' -f2)
    mcp_rpc '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
}
```

The script prints a readable summary of everything the server advertised and returned; it never starts or stops the stack, and it never assumes a tool surface, it reads `tools/list` fresh on each run and calls only what is named there.

The tool-coverage witness asserts `tools/list` advertises exactly the expected set, the server's three tools and no others, diffed against a committed expected-tools file, and that the committed `.mcp.json` points at the same server the check just talked to. Each advertised tool is then called for the seeded order and checked against the server's seeded data, the composed status call returning the order's shipped state and its tracking number in one response.

The negative witness is what makes the coverage check mean something. `deleteOrder`, a name the server never advertises, is called directly:

```bash
an_unadvertised_tool_cannot_be_driven() {
    mcp_rpc_tolerant '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"deleteOrder", ...}}' > "$f"
    jq -e '(.error != null) or (.result.isError == true)' "$f" >/dev/null
}
```

The call must come back rejected, either a JSON-RPC-level error or a tool result with `isError` true, never a plain successful result. That the call fails at all, scripted or from an interactive session, proves the MCP surface itself, not an out-of-band convention a client happens to respect, is what keeps a consumer from driving a capability the server never discovered to it. A sanitized, real Claude Code session, captured non-interactively and filtered of any machine identifier before being committed, confirms the same lifecycle produces the same answer from an actual client: order `O-9001` comes back shipped, carrier `FASTLINE`, tracking `FL-778821`, in one tool call.

This example reuses an existing packaged MCP tool server unchanged; it has nothing of its own to build, only a client configuration and a script that drives what the server already exposes.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** a consumer must be able to register an MCP server, discover its actual tool, resource and prompt surface, and drive only what that surface advertises, with no capability reachable that `tools/list` does not name.
> **Contract shape:** operation intent "connect to a registered MCP server and drive its advertised surface"; inputs a server URL and transport, then a tool name and arguments per call; response shape the negotiated protocol revision, the advertised tool/resource/prompt lists, and each call's own result; error shape a rejected call, JSON-RPC error or an `isError` result, for any name `tools/list` does not advertise; idempotency key not needed for a read-only discovery and query lifecycle.
> **Build it here:** "Using `mcp-client.sh` and `.mcp.json` as the model, point a second scripted client at the same server over stdio instead of Streamable HTTP, and confirm it discovers and calls the identical tool surface."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): a scripted MCP client using the official Python MCP SDK that performs initialize, tools/list, and a tools/call for an advertised tool, and asserts a call to an unadvertised tool name is rejected."
> Check: a call to any tool name absent from the server's own `tools/list` is rejected every time, regardless of which client, scripted or interactive, makes the call.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack comes up with the packaged MCP tool server and the shared infrastructure, the MCP handshake negotiates the current protocol revision, the tool-coverage and negative witnesses above run, and the stack is torn down. It ends at 16/16.

Try:

- Set `KEEP=1 ./verify.sh`, then run `./mcp-client.sh` and a real `claude` session side by side against the same running server.
- Point `mcp-client.sh` at a different URL and watch the same lifecycle run, or fail cleanly, against it.
- Edit `.mcp.json`'s `url` to a path nothing answers at and see the next tool call fail.

## Anti-patterns

- **Hand-waving a tool surface from memory.** Assuming a server still advertises a tool because it did last week, instead of reading `tools/list` fresh, is exactly the drift a coverage check exists to catch.
- **Treating a transcript as a test.** A recorded interactive session shows that one conversation worked once; it proves nothing about the next question, the next server version, or the next reader's machine.
- **An unpinned server URL.** A client configuration with no committed, checked value for where the server lives drifts silently the moment the server moves, with no failure until a call lands somewhere unexpected.

## When not to use it

A server with no committed tool manifest and no stable surface to check against gives a scripted client nothing repeatable to assert; driving it only by hand, treating each session as exploratory rather than a pass or fail check, fits the server at that stage better. A one-off exploratory session against a server still under active design gains little from scripting a lifecycle check that will need rewriting on every iteration.

## References

- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
- Anthropic, *Claude Code documentation*, MCP: [docs.claude.com/en/docs/claude-code/mcp](https://docs.claude.com/en/docs/claude-code/mcp)
- Anthropic, *Writing effective tools for AI agents*: [anthropic.com/engineering/writing-tools-for-agents](https://www.anthropic.com/engineering/writing-tools-for-agents)
