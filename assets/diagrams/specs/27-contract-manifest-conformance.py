"""A readiness probe only confirms the process answers; it never reads a line of the contract
or manifest that process publishes. The conformance harness runs a pinned Schemathesis container
against the running service by its network name, diffs the served OpenAPI against the committed
contract, and diffs the served MCP tools list against the committed manifest, then proves the
checks discriminate by running the same checks against a committed drifted copy of each."""

from generate_diagram import emit

emit(
    "27-contract-manifest-conformance",
    1060,
    480,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa", "label": "Readiness probe (not enough)"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa", "label": "Contract and manifest conformance"},
    ],
    nodes=[
        {"x": 60, "y": 90, "w": 400, "h": 50, "style": "ghost",
         "lines": ["service answers /q/health/ready", "the process is up"]},
        {"x": 60, "y": 170, "w": 400, "h": 60, "style": "sub",
         "lines": ["the contract and the manifest", "are never read by this check"]},
        {"x": 600, "y": 80, "w": 400, "h": 50, "style": "accent",
         "lines": ["Schemathesis (pinned), one-shot container", "reaches the service by its network name"]},
        {"x": 600, "y": 150, "w": 400, "h": 46, "style": "box",
         "lines": ["served /q/openapi vs committed contract", "normalized, sorted-key JSON diff"]},
        {"x": 600, "y": 216, "w": 400, "h": 46, "style": "box",
         "lines": ["served MCP tools/list vs committed manifest", "same tool set, same required fields"]},
        {"x": 600, "y": 282, "w": 400, "h": 56, "style": "accent",
         "lines": ["drifted fixture bites", "same checks run again, both now fail"]},
        {"x": 30, "y": 390, "w": 1000, "h": 60, "style": "ink",
         "lines": ["No source in either reused service changes: only the committed fixture the check points at changes, and that alone is enough to flip every result from pass to fail."]},
    ],
    edges=[
        {"x1": 800, "y1": 130, "x2": 800, "y2": 150, "amber": True},
        {"x1": 800, "y1": 196, "x2": 800, "y2": 216, "amber": True},
        {"x1": 800, "y1": 262, "x2": 800, "y2": 282, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Conformance compares what a service serves against what it commits to"},
    ],
)
