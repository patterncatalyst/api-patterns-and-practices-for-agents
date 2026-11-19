"""Three agents coordinate only through events on shared topics, with no orchestrator calling any
of them in sequence; dropping the one link between shipping and notification (the negative
witness) stalls exactly that hop, leaving the earlier two agents' own work untouched."""

from generate_diagram import emit

emit(
    "18-multi-agent-choreography",
    1100,
    440,
    bands=[
        {"x": 20, "y": 40, "w": 1060, "h": 300, "fill": "#fafafa", "label": "No orchestrator: each agent reacts only to a topic"},
    ],
    nodes=[
        {"x": 50, "y": 100, "w": 260, "h": 56, "style": "accent", "lines": ["order agent", "places the order, publishes the event"]},
        {"x": 420, "y": 100, "w": 260, "h": 56, "style": "accent", "lines": ["shipping agent", "schedules a shipment, publishes the event"]},
        {"x": 790, "y": 100, "w": 260, "h": 56, "style": "accent", "lines": ["notification agent", "writes a notification, terminal"]},
        {"x": 50, "y": 200, "w": 260, "h": 40, "style": "sub", "lines": ["apa.orders.placed"]},
        {"x": 420, "y": 200, "w": 260, "h": 40, "style": "sub", "lines": ["apa.shipping.scheduled"]},
        {"x": 420, "y": 270, "w": 260, "h": 46, "style": "ghost",
         "lines": ["filter() drops the event", "negative witness, not the default"]},
        {"x": 60, "y": 320, "w": 980, "h": 20, "style": "ink", "lines": [""]},
    ],
    edges=[
        {"x1": 310, "y1": 128, "x2": 420, "y2": 128, "amber": True, "label": "event only"},
        {"x1": 680, "y1": 128, "x2": 790, "y2": 128, "amber": True, "label": "event only"},
        {"x1": 180, "y1": 156, "x2": 180, "y2": 200},
        {"x1": 550, "y1": 156, "x2": 550, "y2": 200},
        {"x1": 550, "y1": 240, "x2": 550, "y2": 270, "dashed": True},
    ],
    notes=[
        {"x": 550, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Cutting one link stalls one hop; the agents before it finish their own work regardless"},
        {"x": 550, "y": 410, "anchor": "middle", "size": 12, "color": "#64748b",
         "text": "With the filter closed: order recorded, shipment scheduled, notification never written"},
    ],
)
