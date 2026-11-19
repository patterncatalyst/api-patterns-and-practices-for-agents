"""A naive 1:1 API-to-MCP wrap leaves an agent to call three separate tools and assemble the
answer itself; the tool/resource/prompt triad composes the same three reads into one tool
response, backs it with a resource an agent fetches once, and a prompt that reads the same
composed data the tool does."""

from generate_diagram import emit

emit(
    "13-mcp-triad",
    1060,
    480,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 340, "fill": "#fafafa",
         "label": "Naive 1:1 API-to-MCP wrap"},
        {"x": 570, "y": 40, "w": 460, "h": 340, "fill": "#fafafa",
         "label": "Tool / resource / prompt triad"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 400, "h": 40, "style": "ghost", "lines": ["tool: getOrder"]},
        {"x": 60, "y": 136, "w": 400, "h": 40, "style": "ghost", "lines": ["tool: getPayment"]},
        {"x": 60, "y": 192, "w": 400, "h": 40, "style": "ghost", "lines": ["tool: getShipment"]},
        {"x": 60, "y": 260, "w": 400, "h": 50, "style": "sub",
         "lines": ["agent calls all three, assembles the", "answer itself in its own context"]},
        {"x": 600, "y": 80, "w": 400, "h": 56, "style": "accent",
         "lines": ["orderStatus tool", "composes order + payment + shipment in one call"]},
        {"x": 600, "y": 160, "w": 190, "h": 46, "style": "box",
         "lines": ["resource", "status playbook, fetch once"]},
        {"x": 810, "y": 160, "w": 190, "h": 46, "style": "box",
         "lines": ["prompt", "summarizeOrderStatus"]},
        {"x": 600, "y": 230, "w": 400, "h": 40, "style": "sub", "lines": ["one call, one composed response"]},
        {"x": 30, "y": 400, "w": 1000, "h": 60, "style": "ink",
         "lines": ["Reducing orderStatus to a passthrough of the raw order row still returns O-9001,",
                   "but the composed payment and shipment fields come back absent: the check that catches it reads the response, not the call."]},
    ],
    edges=[
        {"x1": 260, "y1": 120, "x2": 260, "y2": 136},
        {"x1": 260, "y1": 176, "x2": 260, "y2": 192, "amber": True},
        {"x1": 260, "y1": 232, "x2": 260, "y2": 260},
        {"x1": 695, "y1": 136, "x2": 695, "y2": 160},
        {"x1": 905, "y1": 136, "x2": 905, "y2": 160},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Packaging an API as MCP tools means composing for the question, not wrapping each endpoint 1:1"},
    ],
)
