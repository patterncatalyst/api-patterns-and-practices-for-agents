# 22 Consuming an MCP server from Claude Code

The Part 4 (Consume) counterpart to 24-langchain4j-programmatic-consumer: instead of an unattended
Quarkus service registering ch13's server as a remote tool, a human points an MCP *client*, Claude
Code, at that same server, discovers its tool, resource and prompt surface, and drives it. Reused
unchanged, exactly as ch13 and 24 reuse it: this example never copies or forks ch13's code.

## The scripted client stand-in, and why

Claude Code is interactive: a transcript records one particular conversation, not a repeatable
check. `mcp-client.sh` is the deterministic, verifiable consumer: a standalone bash and curl and
jq script that performs the exact client lifecycle a consumer (Claude Code or any other MCP
client) performs the moment it connects: `initialize`, `notifications/initialized`, `tools/list`,
`resources/list`, `prompts/list`, a `tools/call` for each of the three advertised tools against
the seeded order `O-9001`, a `resources/read` of the playbook, and a `prompts/get` of
`summarizeOrderStatus`. It prints a readable summary of everything the server advertised and
returned. `verify.sh` drives the same lifecycle as its observable checks; `mcp-client.sh` is the
standalone artifact a reader runs by hand to see what a consumer does.

## `.mcp.json`: registering the server

The committed `.mcp.json` is the real Claude Code MCP client configuration. It registers ch13's
server under the name `apa-orders`, using the Streamable HTTP transport (`"type": "http"`) at the
compose-published URL:

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

A Claude Code session started in this directory picks up `.mcp.json` automatically and can call
`orderStatus`, `paymentStatus` and `shipmentTracking`, read `skill://orders/status-playbook`, and
get `summarizeOrderStatus`, the same surface `mcp-client.sh` drives.

## The tool-coverage witness

`verify.sh` asserts `tools/list` advertises **exactly** the expected set
(`contracts/22-expected-tools.txt`: `orderStatus`, `paymentStatus`, `shipmentTracking`), no more
and no fewer, and that the committed `.mcp.json` points at the same server the check just talked
to. Each advertised tool is then called for `O-9001` and checked against the server's seeded data
(`orderStatus` composes `SHIPPED` and `FL-778821` in one response, the same shapes ch13's own
`verify.sh` asserts).

## The negative witness

A consumer bound to the advertised surface can only call what `tools/list` names. `verify.sh`
calls `tools/call` with `deleteOrder`, a name absent from `tools/list`, and asserts the server
rejects it, either a JSON-RPC-level error or a tool result with `isError` true, never a plain
successful result. The check passes only when that call is actually rejected, which proves the
MCP surface, not an out-of-band convention, is what keeps a consumer, scripted or an interactive
Claude Code session alike, from driving a capability the server never discovered to it.

## How the transcript was captured

`transcript.md` carries a real, sanitized Claude Code session, not a hand-written one.
`capture-transcript.sh` assumes ch13's server is already up (`./verify.sh` with `KEEP=1`) and
`.mcp.json` is in place, runs `claude -p` non-interactively with that `.mcp.json` and a prompt
asking it to look up `O-9001`'s status using the MCP tools, and pipes the captured output through
`../../scripts/host_ids.py filter --repo ../..` before writing `transcript.md`, so no machine or
account identifier from the capturing host ever reaches the committed file.

## Run

```bash
./verify.sh
```

A fresh stack comes up (ch13's server plus the shared infrastructure and LGTM), the MCP handshake
negotiates protocol revision `2025-11-25`, the tool-coverage and negative witnesses above run, and
the stack is torn down. Set `KEEP=1` to leave the stack running, then try the scripted client and
a real Claude Code session by hand:

```bash
KEEP=1 ./verify.sh
./mcp-client.sh                      # the scripted stand-in
claude                               # an interactive session; .mcp.json registers apa-orders
```

- Point `mcp-client.sh` at a different URL (`./mcp-client.sh http://localhost:8080` or
  `MCP_SERVER_URL=...`) and see the same lifecycle run against it.
- Ask Claude Code about an order id other than `O-9001` and see which tool calls it makes to
  answer.
- Edit `.mcp.json`'s `url` to a path nothing answers at and see the next tool call fail.

## Layout

| Path | Purpose |
|------|---------|
| `.mcp.json` | the Claude Code MCP client configuration registering ch13's server as `apa-orders` over Streamable HTTP |
| `mcp-client.sh` | the standalone, deterministic JSON-RPC client: the scripted stand-in for an interactive session |
| `compose.yaml` | includes the shared infrastructure and builds ch13's server as its own service, published to the host |
| `verify.sh` | the checks above, including the tool-coverage and negative witnesses |
| `capture-transcript.sh` | runs a real, non-interactive Claude Code session and writes the sanitized result to `transcript.md` |
| `transcript.md` | the recorded, sanitized Claude Code session |
| `../../contracts/22-expected-tools.txt` | the committed, sorted tool-name set `tools/list` must match exactly |
