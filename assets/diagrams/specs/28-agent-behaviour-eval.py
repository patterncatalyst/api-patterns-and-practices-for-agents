"""A live model run and a replayed committed fixture both produce the identical Transcript shape,
a captured tool-call sequence, token usage and a durable outcome, never the model's own prose.
BehaviourEvaluator scores either one through the same six ordered assertions, stopping at the
first one a transcript violates, and EvalRecorder writes the verdict durably either way."""

from generate_diagram import emit

emit(
    "28-agent-behaviour-eval",
    1060,
    580,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 110, "fill": "#fafafa", "label": "Live run"},
        {"x": 570, "y": 40, "w": 460, "h": 110, "fill": "#fafafa", "label": "Replayed fixture"},
    ],
    nodes=[
        {"x": 60, "y": 72, "w": 400, "h": 56, "style": "accent",
         "lines": ["AgentBehaviourAssistant (Ollama)", "captures Result.toolExecutions()"]},
        {"x": 600, "y": 72, "w": 400, "h": 56, "style": "accent",
         "lines": ["committed JSON-lines fixture", "good-run.jsonl or regressed-run.jsonl"]},
        {"x": 230, "y": 180, "w": 600, "h": 54, "style": "box",
         "lines": ["Transcript", "tool calls, token usage, durable outcome, never model prose"]},
        {"x": 280, "y": 268, "w": 500, "h": 42, "style": "accent",
         "lines": ["BehaviourEvaluator", "six ordered assertions, one committed eval spec"]},
        {"x": 280, "y": 322, "w": 500, "h": 120, "style": "sub",
         "lines": ["first violation wins",
                   "unexpected-tool-called",
                   "call-budget-exceeded",
                   "arg-error-present",
                   "token-budget-exceeded",
                   "refund-not-completed",
                   "final-status-mismatch"]},
        {"x": 280, "y": 460, "w": 500, "h": 50, "style": "accent",
         "lines": ["EvalRecorder", "durable row, counter and log line, keyed by verdict"]},
        {"x": 30, "y": 525, "w": 1000, "h": 45, "style": "ink",
         "lines": ["The same committed spec scores a live run and a replayed fixture identically: editing the recorded outcome, not the spec, is what flips a verdict."]},
    ],
    edges=[
        {"x1": 260, "y1": 128, "x2": 450, "y2": 180, "amber": True},
        {"x1": 800, "y1": 128, "x2": 610, "y2": 180, "amber": True},
        {"x1": 530, "y1": 234, "x2": 530, "y2": 268},
        {"x1": 530, "y1": 310, "x2": 530, "y2": 322},
        {"x1": 530, "y1": 442, "x2": 530, "y2": 460, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "The evaluator scores a live run and a replayed fixture the same way"},
    ],
)
