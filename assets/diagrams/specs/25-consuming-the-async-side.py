"""A disabled consumer leaves a delivered event sitting unconsumed on its topic, never lost;
the active consumer wakes a composing agent and writes the durable record from the event's own
fields under a conflict guard keyed on order id and event type, so a redelivered event adds no
second row."""

from generate_diagram import emit

emit(
    "25-consuming-the-async-side",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa", "label": "Consumer disabled (negative witness)"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa", "label": "Consuming the async side"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 400, "h": 50, "style": "ghost",
         "lines": ["ShipmentDelivered sits on apa.shipping.delivered", "at its committed offset, unread"]},
        {"x": 60, "y": 160, "w": 400, "h": 50, "style": "sub",
         "lines": ["autoStartup(consumerEnabled) stopped the route;", "no row is ever written while it is down"]},
        {"x": 600, "y": 80, "w": 400, "h": 46, "style": "accent",
         "lines": ["ShipmentDeliveredRoute", "Camel Kafka consumer on apa.shipping.delivered"]},
        {"x": 600, "y": 150, "w": 400, "h": 46, "style": "box",
         "lines": ["NotificationBean.notify", "builds Notification from the event's own fields"]},
        {"x": 600, "y": 220, "w": 400, "h": 46, "style": "box",
         "lines": ["NotificationComposerAgent.compose", "quarkus-langchain4j-ollama, no tool, message text only"]},
        {"x": 600, "y": 290, "w": 400, "h": 50, "style": "accent",
         "lines": ["NotificationRecorder.record", "ON CONFLICT (order_id, event_type) DO NOTHING"]},
        {"x": 30, "y": 390, "w": 1000, "h": 50, "style": "ink",
         "lines": ["Stopping the consumer leaves the event exactly where it fell: a restarted consumer reads it from the offset it left off at, and a redelivered event adds no second row."]},
    ],
    edges=[
        {"x1": 260, "y1": 130, "x2": 260, "y2": 160},
        {"x1": 800, "y1": 126, "x2": 800, "y2": 150, "amber": True},
        {"x1": 800, "y1": 196, "x2": 800, "y2": 220, "amber": True},
        {"x1": 800, "y1": 266, "x2": 800, "y2": 290, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "The event wakes the agent; the record it writes survives a repeat of the same event"},
    ],
)
