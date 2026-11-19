"""Two gates, not one. A contract-drift gate reruns the same conformance and manifest-diff checks
ch27 built against the running services; a schema-compatibility gate posts a candidate event-schema
version straight at Apicurio Registry under its COMPATIBILITY rule. Neither gate is a script
pretending to judge compatibility: the registry's own rule engine renders the verdict for gate (b),
and the committed contract is the verdict for gate (a). A pull request merges only when both hold."""

from generate_diagram import emit

emit(
    "32-ci-for-agent-facing-apis",
    1060,
    520,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 390, "fill": "#fafafa", "label": "Gate (a): contract drift"},
        {"x": 570, "y": 40, "w": 460, "h": 390, "fill": "#fafafa", "label": "Gate (b): schema compatibility"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 400, "h": 50, "style": "accent",
         "lines": ["Schemathesis (pinned), one-shot container", "runs the committed OpenAPI contract"]},
        {"x": 60, "y": 150, "w": 400, "h": 46, "style": "box",
         "lines": ["served /q/openapi vs committed contract", "normalized diff"]},
        {"x": 60, "y": 216, "w": 400, "h": 46, "style": "box",
         "lines": ["served MCP tools/list vs committed manifest", "same tool set, same required fields"]},
        {"x": 60, "y": 282, "w": 400, "h": 56, "style": "accent",
         "lines": ["drifted fixture bites", "same checks run again, both now fail"]},
        {"x": 60, "y": 360, "w": 400, "h": 50, "style": "ink",
         "lines": ["merge blocked: served contract or manifest", "no longer matches what is committed"]},
        {"x": 600, "y": 80, "w": 400, "h": 50, "style": "accent",
         "lines": ["candidate schema version", "POSTed straight to Apicurio Registry"]},
        {"x": 600, "y": 150, "w": 400, "h": 46, "style": "box",
         "lines": ["COMPATIBILITY rule: BACKWARD", "the registry's own rule engine checks it"]},
        {"x": 600, "y": 216, "w": 190, "h": 56, "style": "user",
         "lines": ["adds an optional field", "HTTP 200, accepted"]},
        {"x": 810, "y": 216, "w": 190, "h": 56, "style": "ghost",
         "lines": ["adds a required field", "HTTP 400, rejected"]},
        {"x": 600, "y": 360, "w": 400, "h": 50, "style": "ink",
         "lines": ["merge blocked: the registry itself", "refused the incompatible version"]},
    ],
    edges=[
        {"x1": 260, "y1": 130, "x2": 260, "y2": 150, "amber": True},
        {"x1": 260, "y1": 196, "x2": 260, "y2": 216, "amber": True},
        {"x1": 260, "y1": 262, "x2": 260, "y2": 282, "amber": True},
        {"x1": 260, "y1": 338, "x2": 260, "y2": 360, "amber": True},
        {"x1": 800, "y1": 130, "x2": 800, "y2": 150, "amber": True},
        {"x1": 700, "y1": 196, "x2": 700, "y2": 216},
        {"x1": 900, "y1": 196, "x2": 900, "y2": 216, "amber": True},
        {"x1": 900, "y1": 272, "x2": 900, "y2": 360, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Two gates block a merge for two different reasons; each check names which one fired"},
    ],
)
