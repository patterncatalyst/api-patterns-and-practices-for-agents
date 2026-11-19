# 24 langchain4j agent as a programmatic consumer

The Part 4 anchor: an unattended langchain4j `AiService` (`OrderStatusConsumerAgent`, backed
directly by `quarkus-langchain4j-ollama`, never the Camel `langchain4j-agent` component) consumes
ch13's `package-api-as-mcp-tools` server as a registered remote tool. `@McpToolBox("apa-orders")`
on the one AiService method registers that server's tools (`orderStatus`, `paymentStatus`,
`shipmentTracking`) as remote tools over Streamable HTTP, configured by
`quarkus.langchain4j.mcp.apa-orders.*`. Alongside the remote tool box, the method also carries one
local tool, `OrderStatusRecorder`, so the model's workflow is: call the remote `orderStatus` tool
for the order id, then call the local `recordConsultation` tool with what it learned. The local
tool is the receipt: it writes a durable `consumer.agent_order_status_consultation` record,
increments a Micrometer counter and logs a line, so the round trip is observable independent of
the model's own wording, the same reasoning `ShipmentPrepTool` states in
17-event-triggered-agent-wakeup.

`ConsumeOrderStatusRoute`, a Camel platform-http route (`POST /consume`, the order id as the
plain-text body, the same convention 00-stack-check's `AssistantRoute` uses), triggers
`ConsumerBean`, a thin `@ActivateRequestContext` CDI bridge.

## MODEL=off: the fixture lane

`agent.consumer.model-source` picks the decision path. `ollama` (the default, and what compose and
the live `verify.sh` always use) lets the real model call the remote tool itself. `fixture` (forced
by the `%test` profile, so `mvn -B verify` and CI never need a model) routes to `FixtureConsumer`,
which bypasses the model entirely and calls the remote `orderStatus` tool **directly** through a
hand-built `dev.langchain4j.mcp.client.DefaultMcpClient` (the same API `@McpToolBox` uses
internally) pointed at the same `quarkus.langchain4j.mcp.apa-orders.url`. Under `%test` that URL
points not at the real ch13 container but at `FixtureOrderStatusMcpServer`, a tiny in-process MCP
tool server (`quarkus-mcp-server-http`, test scope only) compiled into this app's own test build,
exposing an equivalent `orderStatus` tool on this same app's own `/mcp` endpoint. So `mvn -B verify`
exercises a real Streamable HTTP MCP round trip, including the `quarkus.langchain4j.mcp.apa-orders.*`
config shape itself, never the live ch13 container and never Ollama. Either path ends at the same
`OrderStatusRecorder.recordConsultation`, so the durable record, the counter and the log line this
example's checks assert on are identical whichever path produced them, mirroring
17-event-triggered-agent-wakeup's `FixtureAdvisor`/`ShipmentPrepTool` record/replay discipline.

Three test-scope MCP-client strategies were weighed (mocking/stubbing the `@McpToolBox` client, an
in-process MCP server, WireMock against `/mcp`): the in-process server was cleanest. It needs no
hand-replicated MCP JSON-RPC session semantics (the WireMock path) and no CDI bean override for a
synthetic, extension-managed client (the mock path); it is a real server speaking the real
protocol, so a regression in the `quarkus.langchain4j.mcp.apa-orders.*` config shape would fail
`mvn -B verify` even though the AiService's own `@McpToolBox` method is never invoked without
Ollama.

## The negative witness (live run only)

`MCP_APA_ORDERS_URL` (demo code) points `@McpToolBox("apa-orders")`'s configured URL at a path no
server answers at. With it broken, the model can never reach the remote `orderStatus` tool, so
`recordConsultation` is never called and no durable row or counter increment follows for an order
never consulted before. Restoring the correct URL (the compose default) and asking about the same
order again records it. This check is the live `verify.sh`'s job (not run by this authoring pass);
see `_plans/verification-log.md` for the recorded run.

## Run

```bash
../../scripts/ollama-model.sh check   # or pull, the first time
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it builds ch13's `package-api-as-mcp-tools`
server, this chapter's consumer and the shared domain model inside their images, starts Kafka,
PostgreSQL, Apicurio, Keycloak, Ollama and LGTM plus both services, runs the checks above (including
the negative witness) and removes everything with `down -v`. Set `KEEP=1` to leave the stack running
for inspection.

Run the tests alone (Quarkus Dev Services starts PostgreSQL; no compose stack, no ch13 container and
no Ollama needed, since the `%test` profile forces the fixture decision path). Install the shared
domain model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Tests and checks in detail

`OrderStatusConsumerTest` (`mvn -B verify`) exercises the whole trigger-to-record path against Dev
Services PostgreSQL and the in-process `FixtureOrderStatusMcpServer`, MODEL=off: consuming a known
order id records its status, an order never consumed has no record, and the health endpoint
reports up.

`verify.sh` adds the live, real-model view: the same trigger-to-record round trip through the real
`OrderStatusConsumerAgent`, Ollama and the real ch13 server over the network, OTel signals reaching
Tempo, Prometheus and Loki, and the negative witness, container-level, with the MCP client's URL
actually broken and restored.

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../ConsumeOrderStatusRoute.java` | the Camel platform-http trigger, `POST /consume` |
| `src/main/java/.../ConsumerBean.java` | the `@ActivateRequestContext` CDI bridge; `agent.consumer.model-source` picks ollama or fixture |
| `src/main/java/.../OrderStatusConsumerAgent.java` | the langchain4j `AiService` interface (quarkus-langchain4j-ollama), `@McpToolBox("apa-orders")` plus the local `OrderStatusRecorder` tool |
| `src/main/java/.../FixtureConsumer.java` | the MODEL=off decision path: calls the remote MCP tool directly through a hand-built `McpClient` |
| `src/main/java/.../OrderStatusRecorder.java` | the one local `@Tool`: the durable write, the Micrometer counter, the log line |
| `src/test/java/.../FixtureOrderStatusMcpServer.java` | test-scope-only in-process MCP server standing in for ch13 under `mvn -B verify` |
| `src/main/resources/db/migration/` | Flyway migration for `consumer.agent_order_status_consultation` |
| `compose.yaml` | includes the shared infrastructure, builds ch13's server as its own service, and adds this consumer |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
