---
title: "Observability for Agent Traffic"
order: 33
part: "Run in production"
description: "A model call leaves a trail you can cost and trace: a GenAI trace in Tempo, a nonzero token-usage metric in Prometheus, a durable per-call cost row in Postgres, and a log line in Loki, without hand-rolling a span."
duration: "20 minutes"
---

## Learning objectives

- Read the GenAI spans and the token-usage metric a framework emits on its own, instead of hand-rolling a span around every model call.
- Register a chat model listener as a plain CDI bean and have it picked up automatically, alongside the framework's own span and metrics listeners.
- Derive a per-call cost from token counts at a fixed, documented rate and write it to a durable row that survives a trace's retention window.
- Confirm a span or metric name against a resolved dependency instead of assuming it from a naming convention, and know when a durable row, not a metric, has to be the witness.

## Why this matters

When an agent calls a model, nobody can answer "how much did that cost" or "why did it say that" from the response alone. A model call that returns a string gives no count of what went in or out, no record of which model answered, and no trail once the process exits. A service that fronts several agents at once, each making several calls, accumulates a cost nobody can attribute to a caller, a feature or a day, until a bill arrives with no way to explain it. Tracing the call alone is not enough either: a trace expires on a retention window, while a cost report needs to look back over a billing period a trace store was never built to hold.

{% include excalidraw.html file="33-observability-for-agent-traffic" alt="One real agent call flows through an AiService span, a chat-model span carrying gen_ai attributes, and a tool-call span and log line, all emitted by the framework on its own; TokenCostRecorder listens to the same call and writes a durable Postgres row, the primary witness, since disabling it still leaves the Tempo span and the Prometheus metric intact" caption="Figure 33.1: One agent call reaches Tempo, Prometheus, Postgres and Loki; the durable row is the one that survives a restart" %}

## Pattern: Observability for Agent Traffic

### The problem

A model call looks like any other outbound call to a service's own code: send a request, get a response. Treated that way, it gives up the two facts that matter most for running it in production: what it cost, and what happened when it failed to behave, attributed to a specific call, a specific caller and a specific moment, not averaged into a dashboard that can only say traffic went up. Hand-rolling a span around the call is tempting and wrong twice over: it duplicates attributes a framework that already understands the call's shape can emit correctly on its own, and a span, however well attributed, still expires. A cost or compliance question asked next quarter needs a row, not a trace.

### The solution

**An agent-facing service reads the GenAI spans and token-usage metric its framework already emits for a model call, and writes its own durable, per-call cost row from the same call's token counts, so a trace answers "what happened" while the row answers "what did it cost" long after the trace is gone.**

### How Quarkus models it

Calling a langchain4j `AiService` method against a real model, with an OpenTelemetry extension and a Micrometer registry present, makes the framework register its own chat-model listeners with no configuration naming them: one opens an OpenTelemetry span for the call and a child span for each tool it invokes, both carrying `gen_ai.*` attributes (the operation name, the request model, the input and output token counts); the other records a token-usage counter, tagged by whether the tokens were input or output, which a metrics backend scrapes. A tool's own log line and a durable write it makes are unaffected by any of this: they are the application's own code, observed the same way any other call would be.

A per-call cost row rides the same signal a different way. A plain CDI bean that implements the chat model's listener interface is discovered by the same typesafe lookup that finds the framework's own listeners, so registering one needs no extra wiring: implement the interface, let the container find it. Its request callback stashes a generated call id in the shared attribute map every listener callback for one call receives; its response callback reads the token counts straight off the response, derives a cost at a fixed, documented per-1000-token rate, and writes one row keyed by that call id. Deriving the rate as a constant rather than calling an external pricing service keeps the number auditable: anyone reading the code can see exactly what produced it.

Confirming which span is actually named what matters more than it sounds. Decompiling the resolved extension jar, rather than assuming a naming convention from its class names, showed the AiService-level span is named after the interface and method that were called, with no added prefix; a string that looks like it should be the span's name turned out to name a different, unrelated default elsewhere in the same processor. The tool-call span's prefix matched the expectation exactly. Confirming both against the real jar, not a guess, is what made the check that follows a check rather than a hope.

That confirmation also explains why the cost row, not the metric, has to be the thing a disabled recorder's absence is checked against. Disabling the recorder and making a fresh call still produces a GenAI span and a token-usage sample, since those come from the framework's own listeners, untouched by anything this service's own code does. A metrics backend also keeps a previous instance's last value live across a restart, so a metric-presence check alone would pass whether or not the recorder ran. The row's absence is the only check that actually distinguishes a disabled recorder from a working one.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** a model call must leave a durable, per-call record of its token counts and derived cost, independent of and outliving any trace the call also produces.
> **Contract shape:** operation intent "record a model call's token cost"; inputs a call id, a model name, an input and an output token count; response shape a persisted row carrying those counts and the cost derived from them at a fixed, documented rate; error shape a failed persist surfaces as a thrown exception, never a silently dropped row; idempotency key the call id, so a retried write never doubles a cost already recorded.
> **Build it here:** "Using `TokenCostRecorder` as the model, add a second fixed rate for a second model name, and confirm with a fresh call that the derived cost changes with the model the response reports, not just with its token counts."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): read whatever token-usage signal the model client library already exposes for a call, and write a durable, per-call cost row derived from it at a fixed, documented rate, so the row outlives the client library's own trace or log line."
> Check: disabling the cost recorder and making a fresh call still produces the framework's own trace and metric for that call, but leaves the durable cost-row count unchanged.

## Build, run, observe

```bash
../../scripts/ollama-model.sh check
./verify.sh
```

A fresh stack brings up Postgres, Ollama and the LGTM stack, makes one real agent call and confirms its trace, metric, row and log line all appear, then disables the cost recorder, makes a fresh call that still answers and still emits its trace and metric, and confirms no new row was written before restoring it and confirming a further call writes one again. A full run finishes 18/18 checks pass.

Try:

- Ask about an order the seeded facts do not name and watch the tool's own log line report it as unknown while the recorder still writes a row for the call that asked.
- Read the fixed per-1000-token rate constants and compute by hand what a call with a given token count should cost, then compare it to the row a real call wrote.
- Query Prometheus for the token-usage metric right after disabling the recorder and notice it still reports the previous call's value, which is exactly why the row, not the metric, is the check that bites.

## Anti-patterns

- **Hand-rolling a span around a model call the framework already instruments.** A span built by hand drifts from the framework's own attribute names the first time the library's conventions change underneath it, while the framework's own span stays current without any added maintenance.
- **Treating a metric's presence as proof an instrumentation path ran.** A metrics backend can hold a previous value live across a restart or a configuration change; only a check that reads a specific row, keyed to a specific call, actually distinguishes a working path from a disabled one.
- **Assuming a span or metric name from a naming convention instead of a resolved dependency.** A name that looks consistent with a sibling convention can still be wrong on the version actually running; only the resolved jar tells you what it really emits.

## When not to use it

A service that never calls a model has nothing for a GenAI span or a token-usage metric to report, and a service whose cost is flat regardless of call volume has no reason to attribute cost per call at all. Writing a per-call cost row before any caller, budget or chargeback process ever reads it is effort spent on a report nobody has asked for yet.

## References

- OpenTelemetry Semantic Conventions for Generative AI systems: [opentelemetry.io/docs/specs/semconv/gen-ai](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
- Quarkus LangChain4j documentation: [docs.quarkiverse.io/quarkus-langchain4j/dev/index.html](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)
- Micrometer documentation: [micrometer.io/docs](https://micrometer.io/docs)
- Grafana LGTM (Loki, Grafana, Tempo, Mimir): [grafana.com/oss](https://grafana.com/oss/)
- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
