"""The measured contrast: the same task against a granular, opaque tool contract
versus a single intent tool, for the same OrderOpsService operation."""

from generate_diagram import emit

emit(
    "01-contract-contrast",
    1060,
    560,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 400, "fill": "#fafafa",
         "label": "Bad contract: getOrder, updateOrderStatus, createRefund"},
        {"x": 570, "y": 40, "w": 460, "h": 400, "fill": "#fafafa",
         "label": "Good contract: cancelAndRefund(orderId, reason)"},
    ],
    nodes=[
        {"x": 60, "y": 90, "w": 400, "h": 56, "style": "box",
         "lines": ["updateOrderStatus(O-1007, CANCELLED)", "accepted"]},
        {"x": 60, "y": 165, "w": 400, "h": 56, "style": "ghost",
         "lines": ["createRefund(guessed amount 25.50)", "opaque error E400"]},
        {"x": 60, "y": 240, "w": 400, "h": 56, "style": "box",
         "lines": ["getOrder(O-1007)", "finds the real amount"]},
        {"x": 60, "y": 315, "w": 400, "h": 56, "style": "ghost",
         "lines": ["createRefund(amount, no orderId)", "opaque error E400"]},
        {"x": 60, "y": 390, "w": 400, "h": 40, "style": "sub",
         "lines": ["the agent gives up: order cancelled, customer not refunded"]},
        {"x": 600, "y": 165, "w": 400, "h": 64, "style": "accent",
         "lines": ["cancelAndRefund(O-1007, reason)", "one call"]},
        {"x": 600, "y": 265, "w": 400, "h": 56, "style": "box",
         "lines": ["order cancelled and the customer refunded", "in the same call"]},
        {"x": 30, "y": 470, "w": 1000, "h": 72, "style": "ink",
         "lines": ["Bad contract: 5 tool calls, 2 argument errors, 3185 tokens, refund not completed",
                   "Good contract: 1 tool call, 0 argument errors, 759 tokens, refund completed"]},
    ],
    edges=[
        {"x1": 260, "y1": 146, "x2": 260, "y2": 165},
        {"x1": 260, "y1": 221, "x2": 260, "y2": 240},
        {"x1": 260, "y1": 296, "x2": 260, "y2": 315},
        {"x1": 260, "y1": 371, "x2": 260, "y2": 390},
        {"x1": 800, "y1": 229, "x2": 800, "y2": 265, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Same task, same model, two contracts for one business operation"},
    ],
)
