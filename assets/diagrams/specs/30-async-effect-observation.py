"""A trigger answers 202 in every case; the real outcome lands later as a gate row, order and
payment rows and topic offsets. The harness polls those effects and confirms a dropped decision
leaves no effect across a bounded window, judging the gate by the effect, never by the exit code."""

from generate_diagram import emit

emit(
    "30-async-effect-observation",
    1060,
    470,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 340, "fill": "#fafafa", "label": "The 202 is the same in every case"},
        {"x": 570, "y": 40, "w": 460, "h": 340, "fill": "#fafafa", "label": "The durable effect is what differs"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 400, "h": 46, "style": "sub",
         "lines": ["trigger: POST .../cancel-request", "answers 202 and holds"]},
        {"x": 60, "y": 160, "w": 400, "h": 50, "style": "ghost",
         "lines": ["held, approved, rejected or dropped:", "the response cannot tell them apart"]},
        {"x": 600, "y": 80, "w": 400, "h": 46, "style": "box",
         "lines": ["gate row + order/payment rows", "read with psql under poll"]},
        {"x": 600, "y": 150, "w": 400, "h": 46, "style": "box",
         "lines": ["pending-decisions and decisions", "topic offsets, keyed by correlation id"]},
        {"x": 600, "y": 220, "w": 400, "h": 46, "style": "accent",
         "lines": ["APPROVE: CANCELLED + REFUNDED", "exactly one execution, held under redelivery"]},
        {"x": 600, "y": 300, "w": 400, "h": 46, "style": "accent",
         "lines": ["dropped decision: holds_for window", "stays PENDING, no effect ever appears"]},
        {"x": 30, "y": 400, "w": 1000, "h": 50, "style": "ink",
         "lines": ["Remove the gate and the pause assertions fail: order CANCELLED and payment REFUNDED the instant the trigger returns, no pending-decision record published."]},
    ],
    edges=[
        {"x1": 260, "y1": 126, "x2": 260, "y2": 160},
        {"x1": 490, "y1": 103, "x2": 600, "y2": 103, "amber": True},
        {"x1": 800, "y1": 126, "x2": 800, "y2": 150, "amber": True},
        {"x1": 800, "y1": 196, "x2": 800, "y2": 220, "amber": True},
        {"x1": 800, "y1": 266, "x2": 800, "y2": 300, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "The effect, not the 202, is what the checks read"},
    ],
)
