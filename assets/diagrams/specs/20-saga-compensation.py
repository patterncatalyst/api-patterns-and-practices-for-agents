"""The outer Camel Saga block runs three nested steps in order, reserve stock, take payment, an
agent books a carrier; a declined payment throws, and the saga compensates every step enlisted so
far, in reverse order, instead of completing."""

from generate_diagram import emit

emit(
    "20-saga-compensation",
    1100,
    460,
    bands=[
        {"x": 20, "y": 40, "w": 1060, "h": 340, "fill": "#fafafa", "label": "Outer saga: direct:fulfillOrder (REQUIRES_NEW, timeout)"},
    ],
    nodes=[
        {"x": 50, "y": 90, "w": 280, "h": 50, "style": "box", "lines": ["reserve stock", "compensation: releaseStock"]},
        {"x": 390, "y": 90, "w": 280, "h": 50, "style": "accent", "lines": ["take payment", "compensation: refundPayment"]},
        {"x": 730, "y": 90, "w": 280, "h": 50, "style": "box", "lines": ["agent books a carrier", "compensation: cancelBooking"]},
        {"x": 390, "y": 180, "w": 280, "h": 44, "style": "ghost", "lines": ["payment declined", "throws, nothing completes"]},
        {"x": 50, "y": 260, "w": 280, "h": 44, "style": "accent", "lines": ["releaseStock runs", "undoes the only step enlisted"]},
        {"x": 390, "y": 260, "w": 280, "h": 44, "style": "sub", "lines": ["refundPayment never runs", "no payment row was ever written"]},
        {"x": 730, "y": 260, "w": 280, "h": 44, "style": "sub", "lines": ["cancelBooking never runs", "the carrier step never started"]},
        {"x": 30, "y": 340, "w": 1000, "h": 24, "style": "ink", "lines": [""]},
    ],
    edges=[
        {"x1": 330, "y1": 115, "x2": 390, "y2": 115, "amber": True},
        {"x1": 670, "y1": 115, "x2": 730, "y2": 115, "amber": True},
        {"x1": 530, "y1": 140, "x2": 530, "y2": 180},
        {"x1": 530, "y1": 224, "x2": 190, "y2": 260, "dashed": True, "amber": True, "label": "compensate in reverse"},
    ],
    notes=[
        {"x": 550, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Every compensation is a guarded, idempotent UPDATE: calling it twice changes nothing the second time"},
        {"x": 550, "y": 420, "anchor": "middle", "size": 12, "color": "#64748b",
         "text": "Disabling compensation leaves the reserved stock reserved, a partial effect surviving the failure"},
    ],
)
