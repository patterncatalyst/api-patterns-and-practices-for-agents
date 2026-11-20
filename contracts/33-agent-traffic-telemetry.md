# Agent traffic telemetry (chapter 33)

The span, metric and log names `examples/33-observability-for-agent-traffic/verify.sh` asserts
on, confirmed by decompiling the resolved `quarkus-langchain4j-core` and
`quarkus-langchain4j-ollama` 1.13.4 jars (`AiServicesProcessor`, `ListenersProcessor`,
`SpanChatModelListener`, `MetricsChatModelListener`, `SpanWrapper`, `ToolSpanWrapper`), not
assumed from documentation.

## Spans (Tempo)

| Span | Name (confirmed) | Attributes |
|---|---|---|
| AiService call | `langchain4j.aiservices.<InterfaceSimpleName>.<methodName>`, here `langchain4j.aiservices.OrderStatusAdvisor.answer` | none of its own; the chat and tool spans nested under it carry the `gen_ai.*` attributes below |
| Chat model call | `completion <model-id>` (the literal text constant is `completion`, independent of the `gen_ai.operation.name` attribute value, which is `chat`) | `gen_ai.operation.name=chat`, `gen_ai.provider.name`, `gen_ai.request.model`, `gen_ai.request.temperature`, `gen_ai.request.top_p`, `gen_ai.response.id`, `gen_ai.response.model`, `gen_ai.response.finish_reasons`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens` |
| Tool call | `langchain4j.tools.<toolName>`, here `langchain4j.tools.orderStatus` | `gen_ai.operation.name=execute_tool`, `gen_ai.tool.call.id`, `gen_ai.tool.name`, `gen_ai.tool.type`; `gen_ai.tool.call.arguments`/`gen_ai.tool.call.result` only when `quarkus.langchain4j.tracing.include-tool-arguments`/`-tool-result` are true, which this example never sets |

### Span names confirmed against the running service

On 1.13.4 the AiService-level span is named `langchain4j.aiservices.<Interface>.<method>`
(`langchain4j.aiservices.OrderStatusAdvisor.answer`) and the tool-call span
`langchain4j.tools.<tool>` (`langchain4j.tools.orderStatus`). Both names were read back from Tempo
for a real call to the running service, which is the authority `verify.sh` asserts against. The
chat-model span's literal name is `completion <model-id>`, independent of its own
`gen_ai.operation.name` attribute value, which is `chat`.

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
