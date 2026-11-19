"""A poll loop either sits idle on a timer or finds out late; the Camel Kafka consumer on
apa.payments.processed wakes the agent's own call path the instant a payment is processed, and
the tool call it makes, not the consumer, is what records the decision."""

from generate_diagram import emit

emit(
    "17-event-triggered-wakeup",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa", "label": "Poll loop (not used here)"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa", "label": "Event-triggered wakeup"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 400, "h": 50, "style": "ghost",
         "lines": ["agent polls on a fixed interval", "for a payment it does not yet know happened"]},
        {"x": 60, "y": 160, "w": 400, "h": 50, "style": "sub",
         "lines": ["idle calls while nothing changed,", "a late reaction when something did"]},
        {"x": 600, "y": 80, "w": 400, "h": 46, "style": "accent",
         "lines": ["PaymentWakeupRoute", "Camel Kafka consumer on apa.payments.processed"]},
        {"x": 600, "y": 150, "w": 400, "h": 46, "style": "box",
         "lines": ["AgentWakeupBean", "activates a request context for the call"]},
        {"x": 600, "y": 220, "w": 400, "h": 46, "style": "box",
         "lines": ["ShipmentAdvisor", "quarkus-langchain4j-ollama, one @Tool"]},
        {"x": 600, "y": 290, "w": 400, "h": 50, "style": "accent",
         "lines": ["ShipmentPrepTool.prepShipment", "durable row, counter, log line, all in one call"]},
        {"x": 30, "y": 390, "w": 1000, "h": 50, "style": "ink",
         "lines": ["Stopping the consumer leaves the event exactly where it fell, never lost: a restarted consumer reads it from the offset it left off at."]},
    ],
    edges=[
        {"x1": 260, "y1": 130, "x2": 260, "y2": 160},
        {"x1": 800, "y1": 126, "x2": 800, "y2": 150, "amber": True},
        {"x1": 800, "y1": 196, "x2": 800, "y2": 220, "amber": True},
        {"x1": 800, "y1": 266, "x2": 800, "y2": 290, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "The event wakes the agent; the tool call it makes is what the checks observe"},
    ],
)
