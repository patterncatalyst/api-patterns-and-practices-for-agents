"""Three stacked guards in front of a two-tool MCP surface: an unauthenticated call is rejected
before the MCP dispatcher runs, a role gates the read, and a scope plus an Elicitation confirm
gate the write."""

from generate_diagram import emit

emit(
    "16-gated-mcp-tools",
    1060,
    480,
    bands=[
        {"x": 30, "y": 40, "w": 1000, "h": 360, "fill": "#fafafa", "label": "/mcp, OIDC bearer required"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 400, "h": 40, "style": "ghost", "lines": ["no bearer token"]},
        {"x": 520, "y": 80, "w": 200, "h": 40, "style": "ghost", "lines": ["401, rejected at the HTTP policy"]},
        {"x": 60, "y": 150, "w": 400, "h": 46, "style": "box", "lines": ["bearer token, role agent"]},
        {"x": 520, "y": 150, "w": 300, "h": 46, "style": "box", "lines": ["orderSummary", "read, no scope required"]},
        {"x": 60, "y": 230, "w": 400, "h": 46, "style": "accent", "lines": ["bearer token, role agent,", "scope mcp:tools:write"]},
        {"x": 520, "y": 230, "w": 300, "h": 46, "style": "accent", "lines": ["Elicitation confirm", "accept or decline, before any write"]},
        {"x": 520, "y": 300, "w": 300, "h": 46, "style": "box", "lines": ["cancelOrder", "write, only on accept"]},
        {"x": 60, "y": 330, "w": 400, "h": 40, "style": "sub", "lines": ["decline returns REJECTED_BY_USER, not an error"]},
        {"x": 30, "y": 420, "w": 1000, "h": 46, "style": "ink",
         "lines": ["@PermissionsAllowed(\"mcp:tools:write\") splits its value on the first colon and never matches this scope; checking the scope claim by hand sidesteps the collision"]},
    ],
    edges=[
        {"x1": 460, "y1": 100, "x2": 520, "y2": 100},
        {"x1": 460, "y1": 173, "x2": 520, "y2": 173, "amber": True},
        {"x1": 460, "y1": 253, "x2": 520, "y2": 253, "amber": True},
        {"x1": 670, "y1": 276, "x2": 670, "y2": 300, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Each tool adds its own requirement on top of the endpoint's own authentication gate"},
    ],
)
