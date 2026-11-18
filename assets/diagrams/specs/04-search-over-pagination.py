"""Raw offset pagination sweeping every page to find a match versus one typed-filter
search call that isolates the target directly."""

from generate_diagram import emit

emit(
    "04-search-over-pagination",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "Raw pagination: sweep every page (size 10)"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "Typed search: one call isolates the target"},
    ],
    nodes=[
        {"x": 60, "y": 90, "w": 400, "h": 44, "style": "ghost", "lines": ["GET /orders?page=0&size=10"]},
        {"x": 60, "y": 144, "w": 400, "h": 44, "style": "ghost", "lines": ["GET /orders?page=1&size=10"]},
        {"x": 60, "y": 198, "w": 400, "h": 44, "style": "ghost", "lines": ["GET /orders?page=2&size=10", "a match appears, but a caller cannot know that in advance"]},
        {"x": 60, "y": 270, "w": 400, "h": 40, "style": "sub", "lines": ["... pages 3, 4, 5 ..."]},
        {"x": 60, "y": 320, "w": 400, "h": 40, "style": "sub",
         "lines": ["6 calls, 6479 bytes, to see every one of the 60 rows"]},
        {"x": 600, "y": 150, "w": 400, "h": 64, "style": "accent",
         "lines": ["GET /orders/search?status=PLACED&itemSku=SKU-2", "typed filter, bound server-side"]},
        {"x": 600, "y": 250, "w": 400, "h": 56, "style": "box",
         "lines": ["exactly 3 matching orders, nothing else", "1 call, 309 bytes"]},
        {"x": 30, "y": 390, "w": 1000, "h": 56, "style": "ink",
         "lines": ["Ignoring the filter breaks the isolation: the one call returns orders of every status and SKU instead"]},
    ],
    edges=[
        {"x1": 800, "y1": 214, "x2": 800, "y2": 250, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Same 60-row table, two ways to find 3 rows"},
    ],
)
