"""An opaque error returns a bare status code with nothing an agent can act on; an RFC 9457
problem response names what was wrong and a remedy specific to it, such as the current state
and the transitions still allowed."""

from generate_diagram import emit

emit(
    "10-instructive-error",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "Opaque error"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "Instructive error (RFC 9457)"},
    ],
    nodes=[
        {"x": 60, "y": 110, "w": 400, "h": 56, "style": "box",
         "lines": ["500, text/plain", "\"Internal Server Error\""]},
        {"x": 60, "y": 200, "w": 400, "h": 56, "style": "sub",
         "lines": ["no type, no detail, no remedy", "an agent has only one fact: it failed"]},
        {"x": 600, "y": 90, "w": 400, "h": 72, "style": "accent",
         "lines": ["409, application/problem+json",
                    "type, title, status, detail",
                    "currentState: SHIPPED, allowedTransitions: [DELIVERED]"]},
        {"x": 600, "y": 200, "w": 400, "h": 56, "style": "box",
         "lines": ["an agent reads the remedy", "and decides what to try next"]},
        {"x": 30, "y": 390, "w": 1000, "h": 56, "style": "ink",
         "lines": ["Removing the @ServerExceptionMapper annotations drops every mapped error back to a bare 500 with no remedy member"]},
    ],
    edges=[
        {"x1": 260, "y1": 166, "x2": 260, "y2": 200},
        {"x1": 800, "y1": 162, "x2": 800, "y2": 200, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "A remedy is not the same information as a status code"},
    ],
)
