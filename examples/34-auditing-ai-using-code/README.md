# 34 auditing AI-using code

The INWARD audit, the counterpart to 21-replayable-agent-memory-event-sourced-audit's audit of
calls made IN to an agent's own tool surface: when this app's own langchain4j code calls another
API (ch13's remote `orderStatus` MCP tool), every outbound call it makes must leave an append-only
audit record, so the calls this code makes are as traceable as the calls made to it.

`AuditedToolCaller` is the single chokepoint every outbound call must pass through, whichever
decision path calls it: the real model, deciding to call this registered LOCAL tool
(`OutboundCallAgent`, backed directly by `quarkus-langchain4j-ollama`, never the Camel
`langchain4j-agent` component), or the MODEL=off fixture path, calling it directly with no model
involved. It is not registered with `quarkus-langchain4j-mcp`'s `@McpToolBox`: that
annotation lets the framework discover and execute the remote tool itself, invisibly to
application code, with no single place to audit the call before it goes out. Instead
`AuditedToolCaller` is a local tool (`@RegisterAiService(tools = AuditedToolCaller.class)`) that,
inside its one method, FIRST appends an audit record to the append-only log
(`apa.audit.outbound-calls`, folded by `AuditProjectionRoute` into `audit.outbound_call_log`, the
same fold-and-replay discipline ch21 uses for its own log), THEN performs the call through a
hand-built `dev.langchain4j.mcp.client.DefaultMcpClient` (the same construction
24-langchain4j-programmatic-consumer's `FixtureConsumer` already uses). The audit record carries
the call's correlation id, its target, a SHA-256 digest of its argument (never the raw argument),
and when it occurred; never the model's own prose.

`OutboundCallResultRecorder` writes the business result of the call (the order status and shipment
tracking number, read out of the remote tool's own JSON response) regardless of whether an audit
record was appended for that call: auditing the call and recording its business effect are two
separate concerns.

## MODEL=off: the fixture lane

`agent.outbound.model-source` picks the decision path. `ollama` (the default, and what compose and
the live `verify.sh` always use) lets the real model decide to call `AuditedToolCaller` itself.
`fixture` (forced by the `%test` profile, so `mvn -B verify` and CI never need a model) calls that
SAME audited method directly, bypassing the model entirely. Either path ends at the identical
audited call, so the audit record this example's checks assert on is appended identically whichever
path produced it. Under `%test`, `AuditedToolCaller`'s target points not at the real ch13 container
but at `FixtureOrderStatusMcpServer`, a tiny in-process MCP tool server (`quarkus-mcp-server-http`,
test scope only) compiled into this app's own test build, exposing an equivalent `orderStatus` tool
on this same app's own `/mcp` endpoint, the same construction ch24 uses for its own fixture lane.

## The biting negative witness (live run only)

`AUDIT_INTERCEPTOR_ENABLED` (demo code) gates the audit-append step inside `AuditedToolCaller`
alone. Left at its default (`true`), every outbound call is audited first. Set to `false`, the
outbound call still succeeds (ch13's remote tool still answers, and the business row downstream
still gets written, carrying ch13's seeded shipment tracking number `FL-778821`, a value this
app's own agent has no way to supply except by the call actually reaching the remote tool), but
no audit record is appended for it: the audit log for that order stays at its prior count.
Restoring the flag and calling again resumes auditing, visible as the audit count increasing
again. This is the live `verify.sh`'s job; `AuditInterceptorDisabledTest` proves the same guard
holds in-process, MODEL=off.

## Run

```bash
../../scripts/ollama-model.sh check   # or pull, the first time
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it builds ch13's `package-api-as-mcp-tools`
server, this chapter's app and the shared domain model inside their images, starts Kafka,
PostgreSQL, Apicurio, Keycloak, Ollama and LGTM plus both services, registers the committed
`contracts/34-outbound-call-audit.schema.json` in Apicurio under the `BACKWARD` compatibility rule,
runs the checks above (including both negative witnesses), and removes everything with `down -v`.
Set `KEEP=1` to leave the stack running for inspection.

Run the tests alone (Quarkus Dev Services starts Kafka and PostgreSQL; no compose stack, no ch13
container and no Ollama needed, since the `%test` profile forces the fixture decision path).
Install the shared domain model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Tests and checks in detail

`OutboundCallAuditTest` (`mvn -B verify`) exercises the whole trigger-to-audit-to-record path
against Dev Services Kafka and PostgreSQL, and the in-process `FixtureOrderStatusMcpServer`,
MODEL=off: consuming an order appends exactly one audit record before the call and records the
business result; a replay from the beginning of the log reproduces the live projection; a mutate
attempt against a committed record is rejected by default; an order never consumed has neither an
audit row nor a business row. `AuditInterceptorDisabledTest` runs the same trigger under a
`QuarkusTestProfile` forcing `audit.interceptor.enabled=false`: the call still succeeds and the
business row is still written, but no audit record is appended.

`verify.sh` adds the live, real-model view: the same trigger-to-audit-to-record round trip through
the real `OutboundCallAgent`, Ollama and the real ch13 server over the network, the audit schema
registered in Apicurio, OTel signals reaching Tempo, Prometheus and Loki, and the container-level
version of the biting negative witness, recreating the `app` service with
`AUDIT_INTERCEPTOR_ENABLED=false` and then restoring it.

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../TriggerRoute.java` | the Camel platform-http trigger, `POST /consume` |
| `src/main/java/.../ConsumerBean.java` | the `@ActivateRequestContext` CDI bridge; `agent.outbound.model-source` picks ollama or fixture |
| `src/main/java/.../OutboundCallAgent.java` | the langchain4j `AiService` interface (quarkus-langchain4j-ollama), registers ONLY `AuditedToolCaller`, returns `Result<String>` |
| `src/main/java/.../AuditedToolCaller.java` | the single audited chokepoint: appends the audit record, then calls the remote tool through a hand-built `McpClient` |
| `src/main/java/.../AuditAppendOutboundRoute.java` | the one Camel route that writes to the log (`direct:audit-append-outbound` to Kafka) |
| `src/main/java/.../AuditProjectionRoute.java` | the one Camel route that folds the log into the live projection |
| `src/main/java/.../AuditProjectionStore.java` | the projection's JDBC reads and writes, including the demo-only, gated mutate |
| `src/main/java/.../ReplayService.java` | rebuilds a projection from the log alone with a disposable consumer group |
| `src/main/java/.../AuditResource.java` | `GET /audit/log/{correlationId}`, `POST /audit/replay`, `POST /audit/rebuild-live`, `POST /audit/demo/mutate` |
| `src/main/java/.../OutboundCallResultRecorder.java` | the business receipt written from the captured remote result |
| `src/test/java/.../FixtureOrderStatusMcpServer.java` | test-scope-only in-process MCP server standing in for ch13 under `mvn -B verify` |
| `src/main/resources/db/migration/` | Flyway migrations for `audit.outbound_call_log`/`audit.outbound_call_replay` and `consumer.outbound_call_result` |
| `contracts/34-outbound-call-audit.schema.json` | the committed JSON Schema (draft-07) for one audit record |
| `compose.yaml` | includes the shared infrastructure, builds ch13's server as its own service, and adds this app |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
