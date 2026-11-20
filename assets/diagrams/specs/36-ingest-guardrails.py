"""A call to POST /ingest/orders first meets the rate limit: an interceptor that runs before the
method body, so an over-limit call never reaches the schema gate at all. A call within the limit
is then checked against the committed draft-07 ingest schema; only a payload that clears both
guardrails is accepted. Either rejection leaves the same kind of evidence: a problem+json body, a
durable row, and a Micrometer counter."""

from generate_diagram import emit

emit(
    "36-ingest-guardrails",
    1300,
    520,
    bands=[
        {"x": 30, "y": 40, "w": 1240, "h": 380, "fill": "#fafafa", "label": "POST /ingest/orders"},
    ],
    nodes=[
        {"x": 60, "y": 90, "w": 200, "h": 46, "style": "box", "lines": ["agent call", "POST /ingest/orders"]},
        {"x": 300, "y": 90, "w": 240, "h": 46, "style": "accent",
         "lines": ["@RateLimit", "5 calls / 30-second window"]},
        {"x": 300, "y": 180, "w": 240, "h": 52, "style": "ghost",
         "lines": ["RateLimitException", "429 problem+json, durable counter"]},
        {"x": 580, "y": 90, "w": 240, "h": 46, "style": "accent",
         "lines": ["schema gate", "draft-07, 36-ingest.schema.json"]},
        {"x": 580, "y": 180, "w": 240, "h": 52, "style": "ghost",
         "lines": ["SchemaViolationException", "400 problem+json, durable counter"]},
        {"x": 860, "y": 90, "w": 240, "h": 46, "style": "box", "lines": ["202 ACCEPTED", "orderId, status"]},
        {"x": 30, "y": 440, "w": 1240, "h": 50, "style": "ink",
         "lines": ["RATE_LIMIT_VALUE_OVERRIDE/_WINDOW and SCHEMA_GATE_ENABLED=false each disable exactly",
                   "one guardrail at a time; the other keeps rejecting"]},
    ],
    edges=[
        {"x1": 260, "y1": 113, "x2": 300, "y2": 113},
        {"x1": 420, "y1": 136, "x2": 420, "y2": 180, "label": "over limit"},
        {"x1": 540, "y1": 113, "x2": 580, "y2": 113, "label": "within limit", "amber": True},
        {"x1": 700, "y1": 136, "x2": 700, "y2": 180, "label": "invalid"},
        {"x1": 820, "y1": 113, "x2": 860, "y2": 113, "label": "valid", "amber": True},
    ],
    notes=[
        {"x": 650, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Either guardrail can reject the call before the other ever runs"},
    ],
)
