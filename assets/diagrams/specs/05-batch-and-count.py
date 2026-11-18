"""N single-id GETs and a fetch-everything count versus one id-keyed batch call and
a count flag that returns only a number."""

from generate_diagram import emit

emit(
    "05-batch-and-count",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "One at a time"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "In one call"},
    ],
    nodes=[
        {"x": 60, "y": 90, "w": 400, "h": 56, "style": "ghost",
         "lines": ["GET /orders/{id} x 10", "10 calls, 890 bytes total"]},
        {"x": 60, "y": 190, "w": 400, "h": 56, "style": "ghost",
         "lines": ["GET /orders (every row)", "1 call, 3718 bytes, counted client-side"]},
        {"x": 60, "y": 290, "w": 400, "h": 40, "style": "sub",
         "lines": ["a caller still has to find the hits and the misses itself"]},
        {"x": 600, "y": 90, "w": 400, "h": 56, "style": "accent",
         "lines": ["POST /orders/batch-get, 10 ids", "1 call, 933 bytes, hits keyed by id + notFound"]},
        {"x": 600, "y": 190, "w": 400, "h": 56, "style": "accent",
         "lines": ["GET /orders/count?status=PLACED", "1 call, 2 bytes, a bare number"]},
        {"x": 600, "y": 290, "w": 400, "h": 40, "style": "box",
         "lines": ["no record body ever answers a count question"]},
        {"x": 30, "y": 390, "w": 1000, "h": 56, "style": "ink",
         "lines": ["Returning the full record list from the count endpoint breaks the guarantee: the body becomes an array, not a number"]},
    ],
    edges=[
        {"x1": 800, "y1": 146, "x2": 800, "y2": 190, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Two shapes, two measured contrasts, same table"},
    ],
)
