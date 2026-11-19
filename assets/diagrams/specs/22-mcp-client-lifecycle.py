"""Claude Code picks up the committed .mcp.json and negotiates initialize, tools/list,
resources/list and prompts/list against a packaged MCP tool server; a scripted JSON-RPC client
drives the identical lifecycle as a deterministic, repeatable check, and a tool absent from
tools/list is rejected rather than driven."""

from generate_diagram import emit

emit(
    "22-mcp-client-lifecycle",
    1060,
    480,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 340, "fill": "#fafafa",
         "label": "Claude Code (interactive session)"},
        {"x": 570, "y": 40, "w": 460, "h": 340, "fill": "#fafafa",
         "label": "mcp-client.sh (scripted, deterministic)"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 400, "h": 46, "style": "box",
         "lines": [".mcp.json", "registers apa-orders over Streamable HTTP"]},
        {"x": 60, "y": 150, "w": 400, "h": 46, "style": "accent",
         "lines": ["initialize / notifications/initialized", "tools, resources, prompts negotiated"]},
        {"x": 60, "y": 220, "w": 400, "h": 46, "style": "box",
         "lines": ["a human question", "drives whichever tool call answers it"]},
        {"x": 60, "y": 290, "w": 400, "h": 46, "style": "sub",
         "lines": ["one transcript, one conversation,", "not a repeatable check"]},
        {"x": 600, "y": 80, "w": 400, "h": 46, "style": "accent",
         "lines": ["same initialize / tools,resources,prompts list", "read fresh on every run"]},
        {"x": 600, "y": 150, "w": 400, "h": 46, "style": "box",
         "lines": ["tools/call for each advertised tool", "against the seeded order"]},
        {"x": 600, "y": 220, "w": 400, "h": 50, "style": "accent",
         "lines": ["tools/call deleteOrder", "a name absent from tools/list"]},
        {"x": 600, "y": 290, "w": 400, "h": 46, "style": "ink",
         "lines": ["rejected: isError true or a JSON-RPC error,", "never a plain successful result"]},
        {"x": 30, "y": 400, "w": 1000, "h": 55, "style": "ink",
         "lines": ["The same MCP surface bounds both columns: a tool tools/list never names cannot be driven,",
                   "whether the caller is a human session or a scripted check."]},
    ],
    edges=[
        {"x1": 260, "y1": 126, "x2": 260, "y2": 150, "amber": True},
        {"x1": 260, "y1": 196, "x2": 260, "y2": 220},
        {"x1": 260, "y1": 266, "x2": 260, "y2": 290},
        {"x1": 800, "y1": 126, "x2": 800, "y2": 150, "amber": True},
        {"x1": 800, "y1": 196, "x2": 800, "y2": 220},
        {"x1": 800, "y1": 270, "x2": 800, "y2": 290, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "A human session and a scripted client drive the same lifecycle against the same surface"},
    ],
)
