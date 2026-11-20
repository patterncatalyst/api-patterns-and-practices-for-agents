"""The final project's focused multi-domain data mesh: three independently owned data products
(orders, payments, shipping) on the shared Kubernetes platform, each with its own database and its
own NodePort output port, under one cross-cutting governance plane, with the end-to-end hop a
payment takes from the facade through the stood-in Kafka event to the woken shipping writer."""

from generate_diagram import emit

emit(
    "38-final-project-data-mesh",
    1200,
    460,
    bands=[
        {"x": 20, "y": 12, "w": 1160, "h": 78, "fill": "#fafafa",
         "label": "Host 127.0.0.1 (verify.sh / an agent caller): NodePorts only, no forwarding"},
        {"x": 20, "y": 104, "w": 760, "h": 250, "fill": "#fafafa",
         "label": "apa namespace: 3 data products, 5 Deployments"},
        {"x": 796, "y": 104, "w": 384, "h": 250, "fill": "#fafafa",
         "label": "apa-data namespace: per-product databases, Kafka, Apicurio"},
    ],
    nodes=[
        {"x": 40, "y": 45, "w": 190, "h": 55, "style": "user",
         "lines": ["curl /mcp", "orders, NodePort 8088"]},
        {"x": 250, "y": 45, "w": 190, "h": 55, "style": "user",
         "lines": ["curl /mcp", "payments facade, NodePort 8089"]},
        {"x": 460, "y": 45, "w": 230, "h": 55, "style": "user",
         "lines": ["kcat -P", "apa.payments.processed, NodePort 9092"]},
        {"x": 710, "y": 45, "w": 220, "h": 55, "style": "user",
         "lines": ["curl /mcp/shipping", "shipping-track, NodePort 8090"]},
        {"x": 950, "y": 45, "w": 210, "h": 55, "style": "user",
         "lines": ["curl registry/v3", "Apicurio, NodePort 8081"]},
        {"x": 40, "y": 150, "w": 190, "h": 70, "style": "accent",
         "lines": ["orders", "read-only, orders database"]},
        {"x": 250, "y": 150, "w": 190, "h": 70, "style": "accent",
         "lines": ["payments-facade", "MCP facade, no database"]},
        {"x": 250, "y": 250, "w": 190, "h": 70, "style": "box",
         "lines": ["payment-service", "gRPC 9000, payments database"]},
        {"x": 460, "y": 150, "w": 190, "h": 70, "style": "accent",
         "lines": ["shipping-track", "/mcp/shipping, shipping database"]},
        {"x": 460, "y": 250, "w": 190, "h": 70, "style": "accent",
         "lines": ["shipping-writer", "Kafka-woken, shipping database"]},
        {"x": 816, "y": 150, "w": 170, "h": 65, "style": "box",
         "lines": ["Kafka (Strimzi)", "apa-kafka-bootstrap:9092"]},
        {"x": 816, "y": 235, "w": 170, "h": 90, "style": "box",
         "lines": ["CloudNativePG", "orders / payments / shipping databases"]},
        {"x": 1000, "y": 150, "w": 160, "h": 65, "style": "sub",
         "lines": ["Apicurio", "BACKWARD per artifact"]},
        {"x": 40, "y": 392, "w": 1140, "h": 48, "style": "ink",
         "lines": ["Federated computational governance: Apicurio BACKWARD gate, Keycloak mcp:tools:write scope, AuditAgentAction/OutboundCallAudit replay"]},
    ],
    edges=[
        {"x1": 135, "y1": 100, "x2": 135, "y2": 150, "label": "NodePort 8088"},
        {"x1": 135, "y1": 220, "x2": 816, "y2": 260, "label": "reads orders db"},
        {"x1": 345, "y1": 100, "x2": 345, "y2": 150, "label": "NodePort 8089"},
        {"x1": 345, "y1": 220, "x2": 345, "y2": 250, "label": "gRPC 9000"},
        {"x1": 440, "y1": 285, "x2": 816, "y2": 290, "label": "writes payments db"},
        {"x1": 575, "y1": 100, "x2": 901, "y2": 150, "amber": True, "label": "produce (NodePort 9092)", "ly": -6},
        {"x1": 901, "y1": 215, "x2": 555, "y2": 250, "label": "consume"},
        {"x1": 555, "y1": 320, "x2": 816, "y2": 310, "label": "writes shipment_prep"},
        {"x1": 820, "y1": 100, "x2": 555, "y2": 150, "label": "NodePort 8090"},
        {"x1": 555, "y1": 220, "x2": 900, "y2": 235, "label": "reads shipping db"},
        {"x1": 1055, "y1": 100, "x2": 1080, "y2": 150, "amber": True, "label": "NodePort 8081", "ly": -6},
    ],
    notes=[
        {"x": 600, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "The final project: a focused multi-domain data mesh"},
        {"x": 600, "y": 450, "anchor": "middle", "size": 11, "color": "#555555",
         "text": "Three independently owned products, one shared platform, one governance plane; every host check goes through a NodePort."},
    ],
)
