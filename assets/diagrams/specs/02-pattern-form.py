"""The anatomy of one catalog entry: the sections every pattern page carries,
in order, plus the optional cross-stack callout a pattern page may add."""

from generate_diagram import emit

emit(
    "02-pattern-form",
    1000,
    600,
    bands=[],
    nodes=[
        {"x": 40, "y": 40, "w": 600, "h": 56, "style": "accent",
         "lines": ["Name", "the pattern's identifier"]},
        {"x": 40, "y": 120, "w": 600, "h": 56, "style": "box",
         "lines": ["Problem", "the pressure that breaks the default design"]},
        {"x": 40, "y": 200, "w": 600, "h": 56, "style": "box",
         "lines": ["Solution", "one bold sentence stating the rule"]},
        {"x": 40, "y": 280, "w": 600, "h": 56, "style": "box",
         "lines": ["How Quarkus and Camel model it", "the concrete classes, routes, annotations"]},
        {"x": 40, "y": 360, "w": 600, "h": 64, "style": "sub",
         "lines": ["Build, run, observe", "the commands, what to watch, the pass count",
                   "ends with a negative witness"]},
        {"x": 40, "y": 450, "w": 600, "h": 56, "style": "box",
         "lines": ["When not to use it", "the trade-off that argues against the rule"]},
        {"x": 40, "y": 530, "w": 600, "h": 50, "style": "sub",
         "lines": ["References", "sources and related pages, never named in running prose"]},
        {"x": 700, "y": 360, "w": 260, "h": 100, "style": "ghost",
         "lines": ["one cross-stack callout", "names a runnable prompt", "and a stack-neutral check"]},
    ],
    edges=[
        {"x1": 340, "y1": 96, "x2": 340, "y2": 120},
        {"x1": 340, "y1": 176, "x2": 340, "y2": 200},
        {"x1": 340, "y1": 256, "x2": 340, "y2": 280},
        {"x1": 340, "y1": 336, "x2": 340, "y2": 360},
        {"x1": 340, "y1": 424, "x2": 340, "y2": 450},
        {"x1": 340, "y1": 506, "x2": 340, "y2": 530},
        {"x1": 640, "y1": 392, "x2": 700, "y2": 410, "amber": True},
    ],
    notes=[
        {"x": 500, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Every pattern page carries these sections, in this order"},
    ],
)
