"""A hand-maintained OpenAPI document loses its enum, required fields and operationId over
time; a document generated from annotated code on every build carries all three because there
is nothing else to fall out of sync."""

from generate_diagram import emit

emit(
    "07-self-describing-contract",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "Hand-maintained document"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "Generated from annotated code"},
    ],
    nodes=[
        {"x": 60, "y": 90, "w": 400, "h": 56, "style": "box",
         "lines": ["status: string", "no enum, no required list, no operationId"]},
        {"x": 60, "y": 170, "w": 400, "h": 64, "style": "ghost",
         "lines": ["a developer forgot to update the document",
                    "when the field became required"],},
        {"x": 60, "y": 260, "w": 400, "h": 56, "style": "sub",
         "lines": ["an agent discovers the gap only by calling it"]},
        {"x": 600, "y": 90, "w": 400, "h": 64, "style": "accent",
         "lines": ["@Schema(required = true), @Schema(enumeration = ...)",
                    "@Operation(operationId = \"placeOrder\")"]},
        {"x": 600, "y": 190, "w": 400, "h": 56, "style": "box",
         "lines": ["openapi: 3.1.x generated on every build",
                    "committed and diffed against the served document"]},
        {"x": 30, "y": 390, "w": 1000, "h": 56, "style": "ink",
         "lines": ["Removing the annotations removes the enum, required fields and operationId from the generated document; the code still runs correctly"]},
    ],
    edges=[
        {"x1": 260, "y1": 146, "x2": 260, "y2": 170},
        {"x1": 260, "y1": 234, "x2": 260, "y2": 260},
        {"x1": 800, "y1": 154, "x2": 800, "y2": 190, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "A generated contract cannot drift from the code, because it is the code"},
    ],
)
