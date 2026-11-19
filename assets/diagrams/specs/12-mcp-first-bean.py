"""Three faces reimplementing the same read independently can drift from each other silently;
three faces delegating to one shared CDI bean return the same result by construction, because
there is only one implementation to call."""

from generate_diagram import emit

emit(
    "12-mcp-first-bean",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "Three independent implementations"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "One bean, three delegating faces"},
    ],
    nodes=[
        {"x": 60, "y": 90, "w": 400, "h": 40, "style": "box", "lines": ["REST reimplements the read"]},
        {"x": 60, "y": 150, "w": 400, "h": 40, "style": "box", "lines": ["GraphQL reimplements the read"]},
        {"x": 60, "y": 210, "w": 400, "h": 40, "style": "ghost", "lines": ["MCP tool reimplements the read, diverges"]},
        {"x": 60, "y": 270, "w": 400, "h": 40, "style": "sub", "lines": ["three results, no guarantee they agree"]},
        {"x": 600, "y": 90, "w": 400, "h": 56, "style": "accent",
         "lines": ["OrderOperations.orderSummary(orderId)", "the one place the read is implemented"]},
        {"x": 600, "y": 180, "w": 126, "h": 40, "style": "box", "lines": ["REST"]},
        {"x": 737, "y": 180, "w": 126, "h": 40, "style": "box", "lines": ["GraphQL"]},
        {"x": 874, "y": 180, "w": 126, "h": 40, "style": "box", "lines": ["MCP tool"]},
        {"x": 600, "y": 250, "w": 400, "h": 40, "style": "sub", "lines": ["same input, same result, every time"]},
        {"x": 30, "y": 390, "w": 1000, "h": 56, "style": "ink",
         "lines": ["Diverging one face from OrderOperations breaks cross-face equality; that face still runs, it just no longer agrees with the other two"]},
    ],
    edges=[
        {"x1": 260, "y1": 130, "x2": 260, "y2": 150},
        {"x1": 260, "y1": 190, "x2": 260, "y2": 210, "amber": True},
        {"x1": 260, "y1": 250, "x2": 260, "y2": 270},
        {"x1": 663, "y1": 146, "x2": 663, "y2": 180},
        {"x1": 800, "y1": 146, "x2": 800, "y2": 180},
        {"x1": 937, "y1": 146, "x2": 937, "y2": 180},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "One bean behind three transports never drifts, because it never duplicates"},
    ],
)
