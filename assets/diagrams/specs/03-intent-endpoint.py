"""Chatty CRUD (two calls, a crash window between them) versus one intent endpoint
that moves order and payment state together, in one transaction."""

from generate_diagram import emit

emit(
    "03-intent-endpoint",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "Chatty CRUD: cancel, then refund, as two calls"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "Intent endpoint: one call moves both"},
    ],
    nodes=[
        {"x": 60, "y": 90, "w": 400, "h": 56, "style": "box",
         "lines": ["PATCH order O-1007 status=CANCELLED", "order moves alone"]},
        {"x": 60, "y": 170, "w": 400, "h": 64, "style": "ghost",
         "lines": ["window: order CANCELLED, payment still COMPLETED",
                    "observable by a reader between the two calls"]},
        {"x": 60, "y": 260, "w": 400, "h": 56, "style": "box",
         "lines": ["POST refund for O-1007", "payment moves alone, later"]},
        {"x": 600, "y": 140, "w": 400, "h": 64, "style": "accent",
         "lines": ["POST /orders/O-1007/cancel-and-refund", "one call, one transaction"]},
        {"x": 600, "y": 240, "w": 400, "h": 56, "style": "box",
         "lines": ["order CANCELLED and payment REFUNDED together", "never observed apart"]},
        {"x": 30, "y": 390, "w": 1000, "h": 56, "style": "ink",
         "lines": ["Removing the saga's transaction boundary lets the order update commit alone: the payment is never refunded"]},
    ],
    edges=[
        {"x1": 260, "y1": 146, "x2": 260, "y2": 170},
        {"x1": 260, "y1": 234, "x2": 260, "y2": 260},
        {"x1": 800, "y1": 204, "x2": 800, "y2": 240, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "One business operation, atomic or not"},
    ],
)
