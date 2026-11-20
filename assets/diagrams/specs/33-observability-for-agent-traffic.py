"""One agent call, instrumented without a hand-rolled span. An HTTP trigger calls an AiService;
quarkus-langchain4j's own listeners open the AiService and chat-model spans and record the
token-usage counter; the tool call gets its own child span and log line; a ChatModelListener this
example owns, TokenCostRecorder, reads the same call's token counts and writes a durable row. Four
destinations, one call: a trace in Tempo, a metric in Prometheus, a row in Postgres, a log line in
Loki."""

from generate_diagram import emit

emit(
    "33-observability-for-agent-traffic",
    1060,
    580,
    bands=[
        {"x": 30, "y": 40, "w": 1000, "h": 230, "fill": "#fafafa", "label": "One real agent call (no Camel route)"},
        {"x": 30, "y": 300, "w": 1000, "h": 150, "fill": "#fafafa", "label": "What quarkus-langchain4j instruments on its own"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 190, "h": 56, "style": "user",
         "lines": ["HTTP GET", "/agent/orders/{id}/status"]},
        {"x": 300, "y": 80, "w": 200, "h": 56, "style": "box",
         "lines": ["AgentTrafficBean", "picks ollama or fixture"]},
        {"x": 550, "y": 80, "w": 230, "h": 56, "style": "accent",
         "lines": ["OrderStatusAdvisor.answer", "AiService-level span"]},
        {"x": 830, "y": 80, "w": 190, "h": 56, "style": "accent",
         "lines": ["langchain4j.tools.orderStatus", "tool-call span + log line"]},
        {"x": 550, "y": 180, "w": 230, "h": 56, "style": "accent",
         "lines": ["chat model call", "gen_ai.* span attributes"]},
        {"x": 830, "y": 180, "w": 190, "h": 56, "style": "ink",
         "lines": ["TokenCostRecorder", "ChatModelListener, this example's own"]},
        {"x": 60, "y": 330, "w": 280, "h": 50, "style": "box",
         "lines": ["Tempo", "OrderStatusAdvisor.answer span"]},
        {"x": 390, "y": 330, "w": 280, "h": 50, "style": "box",
         "lines": ["Prometheus", "gen_ai_client_token_usage_total"]},
        {"x": 60, "y": 400, "w": 280, "h": 50, "style": "ink",
         "lines": ["Postgres", "agenttraffic.token_cost row"]},
        {"x": 390, "y": 400, "w": 280, "h": 50, "style": "box",
         "lines": ["Loki", "tool and recorder log lines"]},
        {"x": 730, "y": 365, "w": 290, "h": 56, "style": "ghost",
         "lines": ["COST_RECORDER_ENABLED=false", "row absent; span and metric unaffected"]},
    ],
    edges=[
        {"x1": 250, "y1": 108, "x2": 300, "y2": 108},
        {"x1": 500, "y1": 108, "x2": 550, "y2": 108},
        {"x1": 780, "y1": 108, "x2": 830, "y2": 108, "amber": True},
        {"x1": 665, "y1": 136, "x2": 665, "y2": 180},
        {"x1": 780, "y1": 208, "x2": 830, "y2": 208, "amber": True},
        {"x1": 200, "y1": 270, "x2": 200, "y2": 330},
        {"x1": 530, "y1": 270, "x2": 530, "y2": 330},
        {"x1": 925, "y1": 236, "x2": 200, "y2": 400, "dashed": True},
        {"x1": 530, "y1": 270, "x2": 530, "y2": 400, "dashed": True},
        {"x1": 925, "y1": 236, "x2": 875, "y2": 365, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "The durable row is the primary witness: it survives a restart; the metric does not discriminate alone"},
    ],
)
