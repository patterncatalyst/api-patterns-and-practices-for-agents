"""The organizing taxonomy: lifecycle as the primary axis, sync versus async as
the secondary axis inside Build, and the seven forces as the third dimension
every entry declares."""

from generate_diagram import emit

emit(
    "02-taxonomy",
    1040,
    480,
    bands=[
        {"x": 20, "y": 280, "w": 1000, "h": 150, "fill": "#fafafa",
         "label": "The forces: the third dimension every entry declares"},
    ],
    nodes=[
        {"x": 20, "y": 50, "w": 180, "h": 64, "style": "accent", "lines": ["Build", "author a tool or endpoint"]},
        {"x": 220, "y": 50, "w": 180, "h": 64, "style": "box", "lines": ["Consume", "call it from an agent harness"]},
        {"x": 420, "y": 50, "w": 180, "h": 64, "style": "box", "lines": ["Test", "contract and behavior checks"]},
        {"x": 620, "y": 50, "w": 180, "h": 64, "style": "box", "lines": ["Ship", "publish the tool surface"]},
        {"x": 820, "y": 50, "w": 180, "h": 64, "style": "box", "lines": ["Run", "observe and guard live traffic"]},
        {"x": 20, "y": 170, "w": 180, "h": 56, "style": "sub", "lines": ["Sync", "REST, GraphQL as MCP tools"]},
        {"x": 220, "y": 170, "w": 180, "h": 56, "style": "sub", "lines": ["Async", "Kafka and Camel wake an agent"]},
        {"x": 40, "y": 320, "w": 220, "h": 50, "style": "box", "lines": ["Context budget"]},
        {"x": 280, "y": 320, "w": 220, "h": 50, "style": "box", "lines": ["Token economy"]},
        {"x": 520, "y": 320, "w": 220, "h": 50, "style": "box", "lines": ["Tool-selection accuracy"]},
        {"x": 760, "y": 320, "w": 220, "h": 50, "style": "box", "lines": ["Error legibility"]},
        {"x": 160, "y": 385, "w": 240, "h": 40, "style": "box", "lines": ["Reasoning, execution boundary"]},
        {"x": 420, "y": 385, "w": 220, "h": 40, "style": "box", "lines": ["Trust boundary"]},
        {"x": 660, "y": 385, "w": 220, "h": 40, "style": "box", "lines": ["Autonomy level"]},
    ],
    edges=[
        {"x1": 60, "y1": 114, "x2": 60, "y2": 170},
        {"x1": 160, "y1": 114, "x2": 280, "y2": 170},
    ],
    notes=[
        {"x": 520, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Lifecycle, then sync or async inside Build, then the forces on every entry"},
    ],
)
