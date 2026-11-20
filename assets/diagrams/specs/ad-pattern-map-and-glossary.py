"""Appendix D's pattern-relationship map: the 34 named patterns (chapters 03 to 36)
placed on the lifecycle axis from Foundations through Build (its sync, MCP and async
sub-lanes), Consume, Test, Ship and Run, with the cross-stage edges that actually
compose in the catalog's own prose, each edge naming the two chapters it connects."""

from generate_diagram import emit

emit(
    "ad-pattern-map-and-glossary",
    1400,
    620,
    bands=[
        {"x": 20, "y": 50, "w": 150, "h": 510, "fill": "#fafafa",
         "label": "Foundations"},
        {"x": 190, "y": 50, "w": 430, "h": 510, "fill": "#fafafa",
         "label": "Build"},
        {"x": 640, "y": 50, "w": 190, "h": 510, "fill": "#fafafa",
         "label": "Consume"},
        {"x": 850, "y": 50, "w": 190, "h": 510, "fill": "#fafafa",
         "label": "Test"},
        {"x": 1060, "y": 50, "w": 150, "h": 510, "fill": "#fafafa",
         "label": "Ship"},
        {"x": 1230, "y": 50, "w": 150, "h": 510, "fill": "#fafafa",
         "label": "Run"},
    ],
    nodes=[
        {"x": 32, "y": 110, "w": 126, "h": 150, "style": "sub",
         "lines": ["Foundations", "(00-02)", "two stacks,", "five pressures,", "pattern form,", "seven forces"]},
        {"x": 202, "y": 90, "w": 406, "h": 130, "style": "accent",
         "lines": ["Build: sync APIs (03-11)", "Intent Endpoint, Search over Pagination,",
                    "Idempotency, Strict Contract,", "Propose/Preview/Commit"]},
        {"x": 202, "y": 240, "w": 406, "h": 130, "style": "accent",
         "lines": ["Build: MCP packaging (12-16)", "MCP-First Single Bean, Package as Tools,",
                    "Domain-Split Discovery,", "Securing and Gating MCP Tools"]},
        {"x": 202, "y": 390, "w": 406, "h": 130, "style": "accent",
         "lines": ["Build: async APIs (17-21)", "Event-Triggered Wakeup, Choreography,",
                    "Human-in-the-Loop Gate,", "Long-Running Workflow, Replayable Audit"]},
        {"x": 655, "y": 160, "w": 162, "h": 240, "style": "box",
         "lines": ["Consume (22-26)", "Claude Code,", "Claude Code Skill,", "LangChain4j Consumer,",
                    "Consuming Async,", "Decision-Rule Review"]},
        {"x": 865, "y": 160, "w": 162, "h": 240, "style": "box",
         "lines": ["Test (27-30)", "Contract and Manifest", "Conformance,", "Behaviour Eval,",
                    "Scripted and Async", "plus HITL tests"]},
        {"x": 1075, "y": 210, "w": 122, "h": 140, "style": "box",
         "lines": ["Ship (31-32)", "Publish Tool", "Surface,", "CI Gates"]},
        {"x": 1245, "y": 160, "w": 122, "h": 240, "style": "box",
         "lines": ["Run (33-38)", "Observability,", "Auditing,", "Identity and Traffic", "Guardrails,",
                    "Kubernetes,", "Final Project"]},
    ],
    edges=[
        {"x1": 158, "y1": 160, "x2": 202, "y2": 150, "label": "pattern form + forces"},
        {"x1": 608, "y1": 150, "x2": 865, "y2": 220, "label": "07/27 contract -> conformance"},
        {"x1": 405, "y1": 220, "x2": 405, "y2": 390, "label": "11/19 preview -> gate"},
        {"x1": 608, "y1": 430, "x2": 655, "y2": 270, "label": "19/26 human or rule"},
        {"x1": 608, "y1": 465, "x2": 655, "y2": 350, "label": "17/25 same wakeup, consuming side"},
        {"x1": 608, "y1": 500, "x2": 865, "y2": 300, "label": "21/28 structured signals only"},
        {"x1": 608, "y1": 300, "x2": 1245, "y2": 260, "amber": True, "label": "16/35 role+scope -> assurance"},
        {"x1": 1197, "y1": 280, "x2": 1245, "y2": 300, "amber": True, "label": "31-32/33-36 gated before production"},
    ],
    notes=[
        {"x": 700, "y": 24, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Appendix D: how the catalog's 34 patterns compose across the lifecycle"},
        {"x": 700, "y": 608, "anchor": "middle", "size": 11, "color": "#555555",
         "text": "Each edge names the two chapters it connects; the full relationship list is the pattern map table."},
    ],
)
