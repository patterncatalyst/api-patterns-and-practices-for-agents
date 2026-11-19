"""An append-only Kafka log folds into a live projection; a replay reads the same log from the
beginning and must reproduce it exactly. A direct mutation that bypasses the log breaks that
agreement, and rebuilding the live projection from the untouched log recovers it."""

from generate_diagram import emit

emit(
    "21-event-sourced-audit",
    1100,
    460,
    bands=[
        {"x": 20, "y": 40, "w": 1060, "h": 340, "fill": "#fafafa", "label": "Both directions, one append-only log"},
    ],
    nodes=[
        {"x": 50, "y": 90, "w": 230, "h": 50, "style": "box", "lines": ["inbound", "the tool call consumed"]},
        {"x": 50, "y": 160, "w": 230, "h": 50, "style": "box", "lines": ["outbound", "the model call, tokens and cost"]},
        {"x": 350, "y": 125, "w": 220, "h": 56, "style": "accent", "lines": ["apa.audit.agent-actions", "append-only, both directions"]},
        {"x": 640, "y": 90, "w": 220, "h": 50, "style": "box", "lines": ["AuditProjectionRoute", "folds the log as it arrives"]},
        {"x": 640, "y": 160, "w": 220, "h": 50, "style": "accent", "lines": ["live projection", "shipping.audit_log_projection"]},
        {"x": 640, "y": 230, "w": 220, "h": 50, "style": "box", "lines": ["replay", "folds the same log from scratch"]},
        {"x": 900, "y": 195, "w": 160, "h": 50, "style": "sub", "lines": ["must match", "live, always"]},
        {"x": 350, "y": 270, "w": 220, "h": 50, "style": "ghost", "lines": ["direct mutate", "bypasses the log (demo only)"]},
        {"x": 30, "y": 340, "w": 1000, "h": 24, "style": "ink", "lines": [""]},
    ],
    edges=[
        {"x1": 280, "y1": 115, "x2": 350, "y2": 140},
        {"x1": 280, "y1": 185, "x2": 350, "y2": 155},
        {"x1": 570, "y1": 150, "x2": 640, "y2": 115, "amber": True},
        {"x1": 760, "y1": 140, "x2": 760, "y2": 160, "amber": True},
        {"x1": 570, "y1": 150, "x2": 640, "y2": 255},
        {"x1": 860, "y1": 240, "x2": 900, "y2": 220},
        {"x1": 760, "y1": 185, "x2": 760, "y2": 230, "dashed": True},
        {"x1": 460, "y1": 270, "x2": 640, "y2": 190, "dashed": True, "amber": True, "label": "breaks the match"},
    ],
    notes=[
        {"x": 550, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "The log is the durable record; rebuilding live from it erases a direct mutation's effect"},
    ],
)
