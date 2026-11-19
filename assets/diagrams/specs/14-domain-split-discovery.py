"""Two small, domain-namespaced servers keep each tools/list short; a shared catalog resource
holds the full 20-operation set, with searchTools resolving a query to either a live tool or a
catalog-only operation."""

from generate_diagram import emit

emit(
    "14-domain-split-discovery",
    1060,
    520,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 260, "fill": "#fafafa", "label": "order server, /mcp/order"},
        {"x": 570, "y": 40, "w": 460, "h": 260, "fill": "#fafafa", "label": "shipping server, /mcp/shipping"},
        {"x": 30, "y": 330, "w": 1000, "h": 130, "fill": "#fff8ef", "label": "shared skill://catalog resource"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 400, "h": 40, "style": "box", "lines": ["order_status_get"]},
        {"x": 60, "y": 136, "w": 400, "h": 40, "style": "box", "lines": ["order_cancel_eligibility_get"]},
        {"x": 60, "y": 192, "w": 400, "h": 46, "style": "accent", "lines": ["searchTools", "same tool on both servers"]},
        {"x": 600, "y": 80, "w": 400, "h": 40, "style": "box", "lines": ["shipping_track_get"]},
        {"x": 600, "y": 136, "w": 400, "h": 40, "style": "box", "lines": ["shipping_eta_get"]},
        {"x": 600, "y": 192, "w": 400, "h": 46, "style": "accent", "lines": ["searchTools", "same tool on both servers"]},
        {"x": 60, "y": 360, "w": 940, "h": 40, "style": "sub",
         "lines": ["20 operations, 10 per domain: 4 are live tools, 16 are catalog-only, read not called"]},
        {"x": 60, "y": 410, "w": 940, "h": 40, "style": "ghost",
         "lines": ["a literal duplicate tool name on one merged server fails at build time, before any test runs"]},
    ],
    edges=[
        {"x1": 260, "y1": 238, "x2": 260, "y2": 330, "amber": True},
        {"x1": 800, "y1": 238, "x2": 800, "y2": 330, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Each server's own tools/list stays small; progressive discovery reaches the rest"},
    ],
)
