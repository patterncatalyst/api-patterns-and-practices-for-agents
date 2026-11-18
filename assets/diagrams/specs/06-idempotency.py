"""A retried POST with no idempotency key duplicates the order and its event; the same
retry carrying an Idempotency-Key replays the first response with a single effect."""

from generate_diagram import emit

emit(
    "06-idempotency",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "Retry without a key"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "Retry with Idempotency-Key: K"},
    ],
    nodes=[
        {"x": 60, "y": 90, "w": 400, "h": 56, "style": "box",
         "lines": ["POST /orders (attempt 1)", "Order row 1, OrderPlaced 1"]},
        {"x": 60, "y": 190, "w": 400, "h": 56, "style": "ghost",
         "lines": ["POST /orders (retry, same body)", "Order row 2, OrderPlaced 2: duplicated"]},
        {"x": 60, "y": 290, "w": 400, "h": 40, "style": "sub",
         "lines": ["two rows, two events, one intended order"]},
        {"x": 600, "y": 90, "w": 400, "h": 56, "style": "accent",
         "lines": ["POST /orders, Idempotency-Key: K (first)", "Order row, OrderPlaced, response stored"]},
        {"x": 600, "y": 190, "w": 400, "h": 56, "style": "accent",
         "lines": ["POST /orders, Idempotency-Key: K (retry, same body)", "stored response replayed, byte for byte"]},
        {"x": 600, "y": 290, "w": 400, "h": 40, "style": "box",
         "lines": ["one row, one event, no matter how many retries"]},
        {"x": 30, "y": 390, "w": 1000, "h": 56, "style": "ink",
         "lines": ["Key K reused with a different body is rejected with 409, naming the order the key was already used for"]},
    ],
    edges=[
        {"x1": 260, "y1": 146, "x2": 260, "y2": 190},
        {"x1": 800, "y1": 146, "x2": 800, "y2": 190, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Safety comes from the server's own guard, not caller discipline"},
    ],
)
