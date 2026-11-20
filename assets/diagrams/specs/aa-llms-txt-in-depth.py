"""The llms.txt convention's four structural pieces, an H1 title, a blockquote summary, H2
sections of annotated links and an Optional section, rendered on every request from a live tool
registry (a ToolManager-backed metadata bean) rather than copied once into a hand-maintained file
that can drift from the real surface."""

from generate_diagram import emit

emit(
    "aa-llms-txt-in-depth",
    1080,
    560,
    bands=[
        {"x": 30, "y": 55, "w": 430, "h": 470, "fill": "#fafafa",
         "label": "Live tool surface (source of truth)"},
        {"x": 500, "y": 55, "w": 250, "h": 470, "fill": "#fafafa",
         "label": "Rendered on every request"},
        {"x": 790, "y": 55, "w": 260, "h": 470, "fill": "#fafafa",
         "label": "/llms.txt (served text/plain)"},
    ],
    nodes=[
        {"x": 55, "y": 95, "w": 380, "h": 70, "style": "box",
         "lines": ["@Tool orderStatus(...)", "name + description declared once",
                   "on the annotated method"]},
        {"x": 55, "y": 185, "w": 380, "h": 70, "style": "box",
         "lines": ["@Tool paymentStatus(...)", "name + description declared once",
                   "on the annotated method"]},
        {"x": 55, "y": 275, "w": 380, "h": 70, "style": "box",
         "lines": ["@Tool shipmentTracking(...)", "name + description declared once",
                   "on the annotated method"]},
        {"x": 55, "y": 375, "w": 380, "h": 60, "style": "sub",
         "lines": ["ToolManager", "the framework's own live tool registry"]},
        {"x": 55, "y": 455, "w": 380, "h": 50, "style": "sub",
         "lines": ["quarkus.application.version", "one build property, read, never hand-typed"]},
        {"x": 525, "y": 180, "w": 200, "h": 90, "style": "accent",
         "lines": ["ToolSurfaceMetadata", "iterates ToolManager,", "sorts by name, reads version"]},
        {"x": 525, "y": 320, "w": 200, "h": 70, "style": "accent",
         "lines": ["LlmsTxtResource", "formats title, summary,", "version, one line per tool"]},
        {"x": 815, "y": 80, "w": 210, "h": 55, "style": "user",
         "lines": ["# H1 title", "the one mandatory line"]},
        {"x": 815, "y": 150, "w": 210, "h": 55, "style": "user",
         "lines": ["> blockquote summary", "one or two sentences"]},
        {"x": 815, "y": 220, "w": 210, "h": 95, "style": "user",
         "lines": ["## H2 section", "annotated links:", "name: what it does,", "when to prefer it"]},
        {"x": 815, "y": 335, "w": 210, "h": 60, "style": "ghost",
         "lines": ["## Optional", "secondary links,", "safe to skip"]},
        {"x": 815, "y": 420, "w": 210, "h": 60, "style": "ink",
         "lines": ["every reader fetches", "the same current index,", "never a stale copy"]},
    ],
    edges=[
        {"x1": 435, "y1": 130, "x2": 525, "y2": 215, "label": "registers"},
        {"x1": 435, "y1": 220, "x2": 525, "y2": 225, "label": "registers"},
        {"x1": 435, "y1": 310, "x2": 525, "y2": 240, "label": "registers"},
        {"x1": 435, "y1": 405, "x2": 525, "y2": 250, "label": "iterated by"},
        {"x1": 435, "y1": 480, "x2": 525, "y2": 265, "label": "read by"},
        {"x1": 625, "y1": 270, "x2": 625, "y2": 320, "label": "tools(), title(), version()"},
        {"x1": 725, "y1": 200, "x2": 815, "y2": 105, "label": "title()"},
        {"x1": 725, "y1": 220, "x2": 815, "y2": 170, "label": "summary()"},
        {"x1": 725, "y1": 340, "x2": 815, "y2": 260, "label": "one line per tool"},
        {"x1": 725, "y1": 365, "x2": 815, "y2": 360, "dashed": True, "label": "deferred entries"},
        {"x1": 920, "y1": 275, "x2": 920, "y2": 420, "amber": True, "label": "fetched fresh, not cached in a file"},
    ],
    notes=[
        {"x": 540, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "llms.txt: four structural pieces, rendered from the live tool surface"},
        {"x": 540, "y": 548, "anchor": "middle", "size": 11, "color": "#555555",
         "text": "A name or description changed on the @Tool method appears in /llms.txt on the next request; nothing is edited twice."},
    ],
)
