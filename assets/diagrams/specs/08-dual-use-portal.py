"""Two independently maintained write-ups of the same API drift apart over time; one shared
metadata bean rendering both the plain-text and HTML surfaces keeps them naming exactly the
same operations."""

from generate_diagram import emit

emit(
    "08-dual-use-portal",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "Two independent write-ups"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "One shared ApiMetadata bean"},
    ],
    nodes=[
        {"x": 60, "y": 90, "w": 400, "h": 56, "style": "box",
         "lines": ["/llms.txt hand-maintained", "names placeOrder, getOrder, cancelOrder"]},
        {"x": 60, "y": 170, "w": 400, "h": 56, "style": "ghost",
         "lines": ["/portal hand-maintained, separately", "names only placeOrder, getOrder"]},
        {"x": 60, "y": 260, "w": 400, "h": 56, "style": "sub",
         "lines": ["the two surfaces disagree", "which one is right?"]},
        {"x": 600, "y": 90, "w": 400, "h": 56, "style": "accent",
         "lines": ["ApiMetadata.operations()", "one list, read by both resources"]},
        {"x": 600, "y": 180, "w": 190, "h": 56, "style": "box",
         "lines": ["/llms.txt", "text/plain"]},
        {"x": 810, "y": 180, "w": 190, "h": 56, "style": "box",
         "lines": ["/portal", "text/html"]},
        {"x": 600, "y": 270, "w": 400, "h": 40, "style": "sub",
         "lines": ["same operation set on both, always"]},
        {"x": 30, "y": 390, "w": 1000, "h": 56, "style": "ink",
         "lines": ["Hardcoding one operation line in /llms.txt, bypassing ApiMetadata, names an operation the portal never learns about"]},
    ],
    edges=[
        {"x1": 260, "y1": 146, "x2": 260, "y2": 170},
        {"x1": 260, "y1": 226, "x2": 260, "y2": 260},
        {"x1": 695, "y1": 146, "x2": 695, "y2": 180, "amber": True},
        {"x1": 905, "y1": 146, "x2": 905, "y2": 180, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "One source, two renderings, never two sources"},
    ],
)
