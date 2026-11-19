"""A fire-and-forget write commits the moment it is called, with no way to see the effect
first; the same intent endpoint with a dryRun flag returns a structured review with zero
writes, a commit applies it, and a REJECTED_BY_USER decision is a graceful no-op."""

from generate_diagram import emit

emit(
    "11-propose-preview-commit",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "Fire-and-forget"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "dryRun on the same endpoint"},
    ],
    nodes=[
        {"x": 60, "y": 110, "w": 400, "h": 56, "style": "box",
         "lines": ["POST /orders/{id}/cancel", "commits immediately, no preview"]},
        {"x": 60, "y": 200, "w": 400, "h": 56, "style": "sub",
         "lines": ["the first visible effect", "is one that can no longer be undone"]},
        {"x": 600, "y": 90, "w": 400, "h": 56, "style": "accent",
         "lines": ["dryRun: true", "ReviewPayload, zero writes, zero events"]},
        {"x": 600, "y": 170, "w": 400, "h": 56, "style": "box",
         "lines": ["commit (dryRun absent)", "order CANCELLED, payment REFUNDED, one event"]},
        {"x": 600, "y": 250, "w": 400, "h": 40, "style": "ghost",
         "lines": ["decision: REJECTED_BY_USER: graceful 200 no-op"]},
        {"x": 30, "y": 390, "w": 1000, "h": 56, "style": "ink",
         "lines": ["Removing the isDryRun() guard lets a preview call commit silently; the real commit after it is then rejected as a duplicate"]},
    ],
    edges=[
        {"x1": 260, "y1": 166, "x2": 260, "y2": 200},
        {"x1": 800, "y1": 146, "x2": 800, "y2": 170, "amber": True},
        {"x1": 800, "y1": 226, "x2": 800, "y2": 250},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "See the effect before it becomes permanent"},
    ],
)
