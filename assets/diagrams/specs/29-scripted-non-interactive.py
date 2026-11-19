"""An exit-code-judged run passes whether or not the contract held; the pinned Newman container
drives the committed collection on the apa network, and the run is judged by the JSON report's
assertion counts and the named unknown-order 404 problem+json assertion, never the exit code."""

from generate_diagram import emit

emit(
    "29-scripted-non-interactive",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa", "label": "Judged by the exit code (not used here)"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa", "label": "Judged by the structured report"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 400, "h": 50, "style": "ghost",
         "lines": ["the runner exits 0", "and the report is never read"]},
        {"x": 60, "y": 160, "w": 400, "h": 50, "style": "sub",
         "lines": ["a 500 text/plain where a 404 problem+json", "belonged can still exit 0"]},
        {"x": 600, "y": 80, "w": 400, "h": 46, "style": "accent",
         "lines": ["pinned Newman, one-shot container", "on the apa network, service DNS"]},
        {"x": 600, "y": 150, "w": 400, "h": 46, "style": "box",
         "lines": ["committed collection", "place, read back, unknown, invalid"]},
        {"x": 600, "y": 220, "w": 400, "h": 46, "style": "box",
         "lines": ["JSON report", "assertions.failed, assertions.total"]},
        {"x": 600, "y": 290, "w": 400, "h": 50, "style": "accent",
         "lines": ["named unknown-order assertion", "404 application/problem+json held"]},
        {"x": 30, "y": 390, "w": 1000, "h": 50, "style": "ink",
         "lines": ["Drift the target's error handling and the report's 404 assertion fails: the run drops from 11/11 to 9/11, read from the report, never the exit code."]},
    ],
    edges=[
        {"x1": 260, "y1": 130, "x2": 260, "y2": 160},
        {"x1": 800, "y1": 126, "x2": 800, "y2": 150, "amber": True},
        {"x1": 800, "y1": 196, "x2": 800, "y2": 220, "amber": True},
        {"x1": 800, "y1": 266, "x2": 800, "y2": 290, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "The report, not the exit code, is what the checks read"},
    ],
)
