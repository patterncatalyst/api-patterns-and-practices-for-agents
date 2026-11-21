"""The apa-order-ops skill's anatomy and how it loads progressively: the front matter name and
description are the only part resident before the skill triggers, the one-paragraph body loads
once a question matches the description, a references/ file loads only when that specific
question needs it, and scripts/order-status.sh runs the live lookup outside the agent's context
entirely."""

from generate_diagram import emit

emit(
    "ae-consuming-skill-end-to-end",
    1080,
    560,
    bands=[
        {"x": 30, "y": 15, "w": 1020, "h": 90, "fill": "#fafafa",
         "label": "Always resident: skills/apa-order-ops/SKILL.md front matter"},
        {"x": 30, "y": 125, "w": 460, "h": 140, "fill": "#fafafa",
         "label": "Loaded once the description matches the question"},
        {"x": 510, "y": 125, "w": 540, "h": 140, "fill": "#fafafa",
         "label": "Loaded only when that question needs it"},
        {"x": 30, "y": 285, "w": 1020, "h": 150, "fill": "#fafafa",
         "label": "Runs outside the agent's context: scripts/order-status.sh against the live server"},
    ],
    nodes=[
        {"x": 60, "y": 45, "w": 460, "h": 55, "style": "accent",
         "lines": ["name: apa-order-ops",
                    "description: “use when an agent needs an order's status, payment, or",
                    "shipment tracking from the apa-orders MCP server…”"]},
        {"x": 560, "y": 45, "w": 460, "h": 55, "style": "box",
         "lines": ["an agent asks: “what is the status of order O-9001?”",
                    "description text matches, the skill triggers"]},
        {"x": 55, "y": 165, "w": 410, "h": 80, "style": "box",
         "lines": ["SKILL.md body (one paragraph)",
                    "default to orderStatus; fall back to a narrower",
                    "tool only when composed is more than asked;",
                    "never call a name tools/list does not advertise"]},
        {"x": 530, "y": 160, "w": 250, "h": 85, "style": "ghost",
         "lines": ["references/order-operations.md",
                    "tool fields + status values",
                    "(loaded for a fields question)"]},
        {"x": 795, "y": 160, "w": 235, "h": 85, "style": "ghost",
         "lines": ["references/troubleshooting.md",
                    "what a failure looks like",
                    "(loaded for a failure question)"]},
        {"x": 60, "y": 320, "w": 300, "h": 90, "style": "user",
         "lines": ["scripts/order-status.sh <orderId>",
                    "checks tools/list, refuses an",
                    "unadvertised operation, then calls"]},
        {"x": 700, "y": 320, "w": 320, "h": 90, "style": "box",
         "lines": ["apa-orders MCP server",
                    "orderStatus, paymentStatus,",
                    "shipmentTracking (read-only)"]},
    ],
    edges=[
        {"x1": 290, "y1": 100, "x2": 290, "y2": 165, "label": "triggers"},
        {"x1": 265, "y1": 245, "x2": 655, "y2": 160, "label": "a fields/values question"},
        {"x1": 400, "y1": 245, "x2": 900, "y2": 160, "label": "a failure-mode question"},
        {"x1": 360, "y1": 320, "x2": 360, "y2": 245, "label": "run it directly"},
        {"x1": 360, "y1": 365, "x2": 700, "y2": 365, "label": "tools/list, then tools/call"},
        {"x1": 700, "y1": 390, "x2": 360, "y2": 390, "dashed": True, "label": "result, or a refusal"},
    ],
    notes=[
        {"x": 540, "y": 500, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Progressive disclosure keeps the resident context small"},
        {"x": 540, "y": 522, "anchor": "middle", "size": 11, "color": "#555555",
         "text": "Only the front matter is always in context; the body, each reference, and the live server"
                 " each load only once a question actually calls for them."},
    ],
)
