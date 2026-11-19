"""The full payload wrapped under a friendlier name still carries every heavy and internal
field; a purpose-built projection, read by its own narrow query, carries only what the task
needs, and GraphQL field selection narrows it further still."""

from generate_diagram import emit

emit(
    "09-agent-bff",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "Wrap: full entity, friendlier name"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa",
         "label": "Reshape: purpose-built projection"},
    ],
    nodes=[
        {"x": 60, "y": 90, "w": 400, "h": 64, "style": "box",
         "lines": ["GET /orders/{id}/summary calls reads.full(id)",
                    "recordUuid, customer, internalNotes, auditTrail"]},
        {"x": 60, "y": 190, "w": 400, "h": 40, "style": "ghost",
         "lines": ["571 bytes, same as the full payload"]},
        {"x": 60, "y": 260, "w": 400, "h": 56, "style": "sub",
         "lines": ["the agent pays for weight it never asked for"]},
        {"x": 600, "y": 90, "w": 400, "h": 56, "style": "accent",
         "lines": ["OrderReadService.summary: its own SELECT",
                    "orderId, customerId, itemSku, quantity, amount, status"]},
        {"x": 600, "y": 170, "w": 190, "h": 40, "style": "box", "lines": ["REST BFF", "110 bytes"]},
        {"x": 810, "y": 170, "w": 190, "h": 40, "style": "box", "lines": ["GraphQL, 2 fields", "62 bytes"]},
        {"x": 600, "y": 230, "w": 400, "h": 40, "style": "sub",
         "lines": ["full REST payload for comparison: 571 bytes"]},
        {"x": 30, "y": 390, "w": 1000, "h": 56, "style": "ink",
         "lines": ["Calling reads.full(id) from the summary endpoint erases the saving: the projection costs the same 571 bytes as the full payload"]},
    ],
    edges=[
        {"x1": 260, "y1": 154, "x2": 260, "y2": 190},
        {"x1": 260, "y1": 230, "x2": 260, "y2": 260},
        {"x1": 695, "y1": 146, "x2": 695, "y2": 170, "amber": True},
        {"x1": 905, "y1": 146, "x2": 905, "y2": 170, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Reshape the read, do not just rename the response"},
    ],
)
