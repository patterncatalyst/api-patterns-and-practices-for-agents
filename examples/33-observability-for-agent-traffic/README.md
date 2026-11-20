# 33 Observability for agent traffic

A single-service example that proves one agent call leaves a trail you can cost and trace. A
plain Quarkus REST resource (no Camel route anywhere in this example) takes an order id and calls
`OrderStatusAdvisor`, a local langchain4j `AiService` backed directly by
`quarkus-langchain4j-ollama`. `OrderStatusAdvisor` calls one tool, `OrderStatusTool.orderStatus`,
which answers from a few seeded, in-memory order facts (`SeededOrderFacts`) and logs a line.
`TokenCostRecorder`, a CDI bean implementing langchain4j's `ChatModelListener`, is picked up by
the chat model's own typesafe listener lookup alongside quarkus-langchain4j's own span and
metrics listeners, with no extra configuration naming it; it reads the real call's token counts
off the model's own response, derives a cost at a fixed, documented per-1000-token rate, and
writes a durable `agenttraffic.token_cost` row, increments a counter and logs a line.

## What the framework instruments on its own

Calling `OrderStatusAdvisor.answer` against a real model, with `quarkus-opentelemetry` and a
Micrometer registry present, makes quarkus-langchain4j register its own `ChatModelListener`
beans automatically: one that opens an OpenTelemetry span for the AiService call and a child span
for the tool call, carrying `gen_ai.*` attributes (`gen_ai.operation.name`, `gen_ai.request.model`,
`gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, and on the tool span
`gen_ai.tool.name`), and one that records the `gen_ai.client.token.usage` Micrometer counter,
tagged `gen_ai.token.type` (`input` or `output`), exposed over Prometheus scraping as
`gen_ai_client_token_usage_total`. Nothing in this example's own code builds a span or that
counter by hand; `TokenCostRecorder` only listens for the token counts the framework already
captured.

### A naming assumption this example does not repeat

Decompiling the resolved extension jar (not assuming it from documentation) showed the
AiService-level span is named `<Interface>.<method>` (`OrderStatusAdvisor.answer` here), never
prefixed `langchain4j.aiservices.`; the tool-call span is named `langchain4j.tools.<tool>`
(`langchain4j.tools.orderStatus` here), matching the common assumption exactly. `verify.sh`
asserts both real, confirmed names against Tempo, not the unverified prefix.

## MODEL=off: the fixture lane

`agent.traffic.model-source` picks the decision path. `ollama` (the default, and what compose and
the live `verify.sh` always use) lets the real model decide and call the tool itself, with
`TokenCostRecorder` reading real token counts off the chat model's own response. `fixture`
(forced by the `%test` profile, so `mvn -B verify` and CI never need a model) routes to
`FixtureTokenUsageAdvisor`, which answers from the same `OrderStatusTool` directly and feeds
`TokenCostRecorder.recordCost` a committed, recorded token count from
`src/main/resources/fixtures/token-usage.jsonl` instead, falling back to a fixed token count for
any order id the fixture does not name. Either path ends at the same `TokenCostRecorder`, so the
durable row, the counter and the log line this example's checks assert on are identical whichever
path produced the token count.

## The negative witness

`cost.recorder.enabled=false` (demo code, `COST_RECORDER_ENABLED` in compose) makes
`TokenCostRecorder.recordCost` return before it ever opens a database connection: no row, no
counter increment, no log line. The GenAI span and the `gen_ai_client_token_usage_total` metric
the framework emits on its own are unaffected, since that flag only governs this example's own
recorder. `verify.sh` disables the recorder, recreates the service, makes a fresh call that still
answers and still emits its GenAI telemetry, and checks that the `token_cost` row count is
unchanged, since Prometheus keeps a previous container instance's last-exported, nonzero sample
live in its TSDB well past a restart, which is exactly why the durable row, not a metric-name
check, is the bite. The recorder is then restored and a further call is checked to grow the row
count again.

A unit-level witness (`TokenCostRecorderUnitTest`, no Dev Services, no CDI) constructs
`TokenCostRecorder` directly with `cost.recorder.enabled=false` and a `null` `DataSource`: a
recorder that tried to open a connection while disabled would throw a `NullPointerException` and
fail the test immediately, instead of quietly skipping the row.

## Run

```bash
../../scripts/ollama-model.sh check   # or pull, the first time
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it builds the service and the shared
domain model inside the image, starts PostgreSQL, Ollama and LGTM plus the service, runs the
checks below (including the negative witness) and removes everything with `down -v`. Set
`KEEP=1` to leave the stack running for inspection.

Run the tests alone (Quarkus Dev Services starts PostgreSQL; no compose stack and no Ollama
needed, since the `%test` profile forces the fixture decision path). Install the shared domain
model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Tests and checks in detail

`ObservabilityForAgentTrafficTest` (`mvn -B verify`) exercises the whole trigger-to-recorder path
against Dev Services PostgreSQL, MODEL=off: an order named in the committed fixture is recorded
with the fixture's own token counts and the cost `TokenCostRecorder.cost` derives from them; an
order the fixture does not name falls back to the fixed fallback token count; the derived cost
grows with either token count. `TokenCostRecorderUnitTest` is the plain, no-CDI unit-level
disabled-recorder witness.

`verify.sh` adds the live, real-model view: the same trigger-to-recorder round trip through the
real `OrderStatusAdvisor` and Ollama, the GenAI span and token-usage metric the framework emits
on its own, the durable row, the tool's and the recorder's own log lines, and the negative
witness, container-level, with the recorder actually disabled and restored.

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../AgentTrafficResource.java` | the trigger: a plain REST resource, no Camel |
| `src/main/java/.../AgentTrafficBean.java` | picks the ollama or fixture decision path |
| `src/main/java/.../OrderStatusAdvisor.java` | the langchain4j `AiService` interface (quarkus-langchain4j-ollama) |
| `src/main/java/.../OrderStatusTool.java` | the one `@Tool`; answers from `SeededOrderFacts` and logs a line |
| `src/main/java/.../SeededOrderFacts.java` | a few seeded, in-memory order facts |
| `src/main/java/.../TokenCostRecorder.java` | the `ChatModelListener`; derives cost, writes the durable row, counts and logs |
| `src/main/java/.../FixtureTokenUsageAdvisor.java` | the MODEL=off decision path |
| `src/main/resources/fixtures/token-usage.jsonl` | the committed, recorded token counts `FixtureTokenUsageAdvisor` replays |
| `src/main/resources/db/migration/` | Flyway migration for `agenttraffic.token_cost` |
| `compose.yaml` | includes the shared infrastructure and LGTM, adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
