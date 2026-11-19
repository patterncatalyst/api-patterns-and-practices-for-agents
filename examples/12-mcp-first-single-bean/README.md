# 12 MCP-first single CDI bean

An order-service that proves chapter 12's thesis: one piece of business logic can serve an agent
over three transports at once without drifting between them. A single `@ApplicationScoped` bean,
`OrderOperations`, holds the read logic, and three faces delegate to it unchanged:

- `OrderResource` exposes it as REST: `GET /orders/{id}/summary`.
- `OrderGraphQLApi` exposes it as a read-only GraphQL query: `orderSummary(orderId)`.
- `OrderMcpTools` exposes it as an MCP tool: `@Tool orderSummary`, served over Streamable HTTP at
  `/mcp` by `quarkus-mcp-server`.

Because all three call the same method on the same bean, the same input returns the same result
on every face. The example seeds one order (`O-9001`) and `verify.sh` fetches its summary three
ways, normalizes each to the same field set, and asserts the three results are identical. It also
runs the MCP handshake (`initialize`, `notifications/initialized`, `tools/list`, `tools/call`),
confirms the server negotiates MCP protocol revision `2025-11-25`, and checks that `tools/list`
advertises `orderSummary` with an input schema that matches the committed
`contracts/12-order-tools.mcp.json`.

This is the bridge into Part 2: it introduces `quarkus-mcp-server` only far enough to show one
bean behind three faces. Packaging, transports in depth, tool discovery, and securing the tool
surface belong to the MCP packaging chapters, not here.

## The negative witness

The point of the shared bean is that the three faces cannot drift. To confirm the cross-face
equality check actually enforces that, one face was changed by hand to stop delegating to
`OrderOperations` and return a different value instead. With one face diverged, the three results
are no longer identical and the "REST, GraphQL and MCP return identical business results" check
fails. Restoring the delegation returns every face to the shared bean and the check passes again.
The bite is recorded in `_plans/verification-log.md`.

## stdio in a detached container

The example depends on both `quarkus-mcp-server-http` and `quarkus-mcp-server-stdio`. The stdio
transport reads standard input, and a container started with `docker compose up -d` has no stdin
attached, so stdio reads EOF and shuts the application down a few seconds after it starts. The
HTTP-serving compose container therefore sets `%prod.quarkus.mcp.server.stdio.enabled=false`.
stdio stays enabled by default for a direct, attached `java -jar` invocation, which is how a
stdio client consumes the server.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack comes up, the three faces are called for `O-9001`, the MCP handshake and
`tools/list` advertisement are checked, and the stack is torn down. All checks pass. Set `KEEP=1`
to leave the stack running.

- Try calling `GET /orders/O-9001/summary`, the GraphQL `orderSummary` query, and the MCP
  `tools/call` for `orderSummary` by hand; the business result is the same on each.
- Try `tools/list` over the MCP endpoint and compare it to `contracts/12-order-tools.mcp.json`.
