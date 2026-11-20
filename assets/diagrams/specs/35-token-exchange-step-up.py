"""A low-assurance, read-scoped token calls the read tool but is rejected by the destructive
tool, leaving the target unchanged. A real RFC 8693 token exchange trades it for a write-scoped
token whose acr claim carries the step-up assurance level, and that exchanged token clears both
the scope gate and the acr floor."""

from generate_diagram import emit

emit(
    "35-token-exchange-step-up",
    1060,
    520,
    bands=[
        {"x": 30, "y": 40, "w": 1000, "h": 400, "fill": "#fafafa", "label": "/mcp, OIDC bearer required"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 400, "h": 46, "style": "box",
         "lines": ["low-assurance token", "scope mcp:tools:read, no acr claim"]},
        {"x": 520, "y": 80, "w": 300, "h": 40, "style": "box", "lines": ["orderSummary", "read, role agent only"]},
        {"x": 520, "y": 150, "w": 300, "h": 46, "style": "ghost", "lines": ["cancelOrder", "rejected: missing mcp:tools:write scope"]},
        {"x": 60, "y": 230, "w": 400, "h": 46, "style": "sub", "lines": ["RFC 8693 token exchange", "subject_token = the token above, audience = apa-mcp"]},
        {"x": 60, "y": 300, "w": 400, "h": 46, "style": "accent",
         "lines": ["exchanged token", "scope mcp:tools:write, acr step-up"]},
        {"x": 520, "y": 300, "w": 300, "h": 46, "style": "accent", "lines": ["cancelOrder", "scope gate clears"]},
        {"x": 520, "y": 370, "w": 300, "h": 40, "style": "box", "lines": ["acr floor clears, order cancelled"]},
        {"x": 30, "y": 450, "w": 1000, "h": 46, "style": "ink",
         "lines": ["STEPUP_ENFORCED=false removes only the acr check; the scope check still runs and still rejects a token missing mcp:tools:write"]},
    ],
    edges=[
        {"x1": 460, "y1": 103, "x2": 520, "y2": 100},
        {"x1": 460, "y1": 103, "x2": 520, "y2": 173, "label": "rejected"},
        {"x1": 260, "y1": 126, "x2": 260, "y2": 230},
        {"x1": 260, "y1": 276, "x2": 260, "y2": 300, "amber": True},
        {"x1": 460, "y1": 323, "x2": 520, "y2": 323, "amber": True},
        {"x1": 670, "y1": 346, "x2": 670, "y2": 370, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "A stronger credential, not a stronger caller, is what clears the destructive gate"},
    ],
)
