"""An agent proposes a refund; reviewRefund hands the proposal to a committed Drools ruleset,
which decides APPROVE or REJECT and names the failed clause; ReviewRecorder writes the verdict
durably. The model is never on the decision path: it can only propose."""

from generate_diagram import emit

emit(
    "af-deterministic-decisioning",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 1000, "h": 130, "fill": "#fafafa",
         "label": "Model proposes"},
        {"x": 30, "y": 190, "w": 1000, "h": 150, "fill": "#fafafa",
         "label": "Rules engine decides"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 440, "h": 50, "style": "box",
         "lines": ["Agent proposal", "orderId, refundAmount, reason"]},
        {"x": 560, "y": 80, "w": 440, "h": 50, "style": "ghost",
         "lines": ["No model on the decision path", "proposes, never decides"]},
        {"x": 60, "y": 230, "w": 440, "h": 50, "style": "accent",
         "lines": ["reviewRefund (MCP tool)", "the agent-facing surface"]},
        {"x": 560, "y": 230, "w": 440, "h": 50, "style": "accent",
         "lines": ["rules/refund-review.drl", "amount, status, reason clauses, salience-ordered"]},
        {"x": 60, "y": 300, "w": 940, "h": 30, "style": "box",
         "lines": ["Same proposal, same rule, same verdict: every time, independent of the model"]},
        {"x": 30, "y": 380, "w": 1000, "h": 60, "style": "ink",
         "lines": ["ReviewRecorder writes an APPROVE or REJECT row naming the failed clause,",
                   "plus a Micrometer counter and a log line: the verdict is observable, not re-derived."]},
    ],
    edges=[
        {"x1": 280, "y1": 130, "x2": 280, "y2": 230},
        {"x1": 500, "y1": 255, "x2": 560, "y2": 255, "amber": True},
        {"x1": 500, "y1": 330, "x2": 500, "y2": 380, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "The model proposes; the committed ruleset decides"},
    ],
)
