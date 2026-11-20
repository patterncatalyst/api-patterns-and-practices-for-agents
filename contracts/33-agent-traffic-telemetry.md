# Agent traffic telemetry (chapter 33)

The span, metric and log names `examples/33-observability-for-agent-traffic/verify.sh` asserts
on, confirmed by decompiling the resolved `quarkus-langchain4j-core` and
`quarkus-langchain4j-ollama` 1.13.4 jars (`AiServicesProcessor`, `ListenersProcessor`,
`SpanChatModelListener`, `MetricsChatModelListener`, `SpanWrapper`, `ToolSpanWrapper`), not
assumed from documentation.

## Spans (Tempo)

| Span | Name (confirmed) | Attributes |
|---|---|---|
| AiService call | `<InterfaceSimpleName>.<methodName>`, here `OrderStatusAdvisor.answer` | none of its own; the chat and tool spans nested under it carry the `gen_ai.*` attributes below |
| Chat model call | `completion <model-id>` (the literal text constant is `completion`, independent of the `gen_ai.operation.name` attribute value, which is `chat`) | `gen_ai.operation.name=chat`, `gen_ai.provider.name`, `gen_ai.request.model`, `gen_ai.request.temperature`, `gen_ai.request.top_p`, `gen_ai.response.id`, `gen_ai.response.model`, `gen_ai.response.finish_reasons`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens` |
| Tool call | `langchain4j.tools.<toolName>`, here `langchain4j.tools.orderStatus` | `gen_ai.operation.name=execute_tool`, `gen_ai.tool.call.id`, `gen_ai.tool.name`, `gen_ai.tool.type`; `gen_ai.tool.call.arguments`/`gen_ai.tool.call.result` only when `quarkus.langchain4j.tracing.include-tool-arguments`/`-tool-result` are true, which this example never sets |

### A naming assumption this chapter's checks do not repeat

The AiService-level span is **not** prefixed `langchain4j.aiservices.` on 1.13.4: that string is
real in the deployment processor, but it names the default Micrometer `@Timed`/`@Counted`
annotation values (`langchain4j.aiservices.timed`, `langchain4j.aiservices.counted`), not the
span. `SpanWrapper.wrap` calls `tracer.spanBuilder(spanInfo.name())` directly on the bare
`<Interface>.<method>` string `AiServicesProcessor.defaultAiServiceSpanName` builds. `verify.sh`
asserts the real name, `OrderStatusAdvisor.answer`, confirmed against the decompiled bytecode of
`AiServiceMethodCreateInfo$SpanInfo` and `SpanWrapper`. The tool span's prefix,
`langchain4j.tools.`, matched the expectation exactly (confirmed in `ToolSpanWrapper`'s own
`StringConcatFactory` bootstrap constant).

## Metrics (Prometheus, via the OTLP to LGTM path)

| Metric | Name (confirmed) | Tags |
|---|---|---|
| token usage | Micrometer counter `gen_ai.client.token.usage`, exposed as `gen_ai_client_token_usage_total` | `gen_ai.operation.name=chat` (becomes `gen_ai_operation_name`), `gen_ai.token.type=input` or `output` (becomes `gen_ai_token_type`); `cache_read`/`cache_creation` variants exist in the jar for providers that report prompt-cache usage, not produced by this example's Ollama model |
| this example's own recorder | Micrometer counter `agenttraffic.cost.recordings`, exposed as `agenttraffic_cost_recordings_total` | none |

`gen_ai.client.token.usage` is registered against `Metrics.globalRegistry` by
`MetricsChatModelListener`, auto-added as a CDI bean whenever `quarkus-opentelemetry` (for the
span listener) and a Micrometer registry capability (for the metrics listener) are both present;
no configuration key enables either listener by name.

## Durable state (PostgreSQL)

| Observation point | Query | Effect it witnesses |
|---|---|---|
| token cost row | `SELECT model, input_tokens, output_tokens, cost_usd FROM agenttraffic.token_cost WHERE model = ?` | one row per model call, the primary witness: it survives a container restart and a Prometheus TSDB holding a stale sample live, which a metric-presence check alone cannot distinguish from a disabled recorder |

## Logs (Loki)

| Observation point | Source | Query shape |
|---|---|---|
| tool call | Loki | `{service_name="observability-for-agent-traffic"} \|= "tool invoked: orderStatus"` |
| recorder write | Loki | `{service_name="observability-for-agent-traffic"} \|= "token cost recorded"` |

## Why the durable row, not the metric, is the negative witness

Recreating the `app` container with `COST_RECORDER_ENABLED=false` still produces a GenAI span and
a `gen_ai_client_token_usage_total` sample for the call, since that telemetry comes from
quarkus-langchain4j's own listeners, unaffected by this example's flag. Prometheus also keeps the
previous container instance's last-exported, nonzero sample live in its time series database well
past the restart, so a metric-name or metric-value check on its own would pass vacuously. The
`token_cost` row count, read directly from PostgreSQL before and after the disabled call, is the
only check that actually discriminates.
