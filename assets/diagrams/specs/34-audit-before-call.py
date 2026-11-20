"""Every outbound call this code's own agent makes is first appended to an append-only log, then
performed. The biting negative witness: with the audit interceptor disabled, the call still
reaches the remote tool and the business result is still written, but no audit record appears."""

from generate_diagram import emit

emit(
    "34-audit-before-call",
    1100,
    460,
    bands=[
        {"x": 20, "y": 40, "w": 1060, "h": 340, "fill": "#fafafa", "label": "One chokepoint, audit before call"},
    ],
    nodes=[
        {"x": 50, "y": 110, "w": 220, "h": 56, "style": "box", "lines": ["model or fixture", "decides to look up an order"]},
        {"x": 340, "y": 90, "w": 230, "h": 50, "style": "accent", "lines": ["AuditedToolCaller", "the single chokepoint"]},
        {"x": 340, "y": 160, "w": 230, "h": 50, "style": "box", "lines": ["1. append first", "apa.audit.outbound-calls"]},
        {"x": 340, "y": 230, "w": 230, "h": 50, "style": "box", "lines": ["2. then call", "ch13 remote orderStatus"]},
        {"x": 650, "y": 160, "w": 210, "h": 50, "style": "accent", "lines": ["live projection", "audit.outbound_call_log"]},
        {"x": 650, "y": 230, "w": 210, "h": 50, "style": "box", "lines": ["business result", "consumer.outbound_call_result"]},
        {"x": 50, "y": 270, "w": 230, "h": 60, "style": "ghost",
         "lines": ["audit.interceptor.enabled=false", "call still succeeds, step 1 skipped"]},
        {"x": 30, "y": 340, "w": 1000, "h": 24, "style": "ink", "lines": [""]},
    ],
    edges=[
        {"x1": 270, "y1": 130, "x2": 340, "y2": 115},
        {"x1": 455, "y1": 140, "x2": 455, "y2": 160, "amber": True},
        {"x1": 455, "y1": 210, "x2": 455, "y2": 230, "amber": True},
        {"x1": 570, "y1": 185, "x2": 650, "y2": 185},
        {"x1": 570, "y1": 255, "x2": 650, "y2": 255},
        {"x1": 165, "y1": 270, "x2": 400, "y2": 170, "dashed": True, "amber": True, "label": "skips step 1 only"},
    ],
    notes=[
        {"x": 550, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Audit first, call second: disabling the interceptor skips only the audit, never the call"},
    ],
)
