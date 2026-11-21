"""The OpenShift-native topology behind the same final-project data mesh: S2I binary builds
pushing ImageStreams into the apa project, edge-TLS Routes in front of the three host-facing
products, OpenShift Service Mesh 3 enforcing namespace-wide STRICT mutual TLS with a PERMISSIVE
PeerAuthentication scoped to each Route-facing service, and the five reused deployables reaching
AMQ Streams Kafka, a per-product Postgres and Ollama."""

from generate_diagram import emit

emit(
    "ab-openshift-local",
    1200,
    520,
    bands=[
        {"x": 20, "y": 12, "w": 1160, "h": 78, "fill": "#fafafa",
         "label": "Host (oc / an agent caller): edge-TLS Routes only, verified with the cluster ingress CA"},
        {"x": 20, "y": 104, "w": 760, "h": 270, "fill": "#fafafa",
         "label": "apa project: S2I builds -> ImageStreams -> 5 Deployments, STRICT mTLS + per-Route PERMISSIVE"},
        {"x": 796, "y": 104, "w": 384, "h": 270, "fill": "#fafafa",
         "label": "apa project: per-product Postgres, AMQ Streams Kafka, Apicurio, Ollama"},
    ],
    nodes=[
        {"x": 40, "y": 45, "w": 180, "h": 55, "style": "user",
         "lines": ["Route: orders", "edge TLS"]},
        {"x": 235, "y": 45, "w": 200, "h": 55, "style": "user",
         "lines": ["Route: payments-facade", "edge TLS"]},
        {"x": 450, "y": 45, "w": 230, "h": 55, "style": "user",
         "lines": ["oc exec: kafka-console-producer", "apa.payments.processed"]},
        {"x": 695, "y": 45, "w": 200, "h": 55, "style": "user",
         "lines": ["Route: shipping-track", "edge TLS"]},
        {"x": 950, "y": 45, "w": 210, "h": 55, "style": "user",
         "lines": ["Route: apicurio", "BACKWARD gate"]},

        {"x": 40, "y": 130, "w": 610, "h": 32, "style": "sub",
         "lines": ["S2I binary builds (ubi10/openjdk-25) -> ImageStreams, named by quarkus.openshift.name"]},

        {"x": 40, "y": 182, "w": 190, "h": 68, "style": "accent",
         "lines": ["orders", "read-only, PERMISSIVE"]},
        {"x": 250, "y": 182, "w": 190, "h": 68, "style": "accent",
         "lines": ["payments-facade", "no database, PERMISSIVE"]},
        {"x": 250, "y": 272, "w": 190, "h": 68, "style": "box",
         "lines": ["payment-service", "gRPC 9000, STRICT only"]},
        {"x": 460, "y": 182, "w": 190, "h": 68, "style": "accent",
         "lines": ["shipping-track", "/mcp/shipping, PERMISSIVE"]},
        {"x": 460, "y": 272, "w": 190, "h": 68, "style": "box",
         "lines": ["shipping-writer", "Kafka-woken, STRICT only"]},

        {"x": 816, "y": 150, "w": 170, "h": 60, "style": "box",
         "lines": ["AMQ Streams Kafka", "apa-kafka-bootstrap:9092"]},
        {"x": 1000, "y": 150, "w": 160, "h": 60, "style": "sub",
         "lines": ["Apicurio", "BACKWARD per artifact"]},
        {"x": 816, "y": 225, "w": 170, "h": 90, "style": "box",
         "lines": ["Postgres (rhel10/postgresql-16)", "orders / payments / shipping / shipping_track"]},
        {"x": 1000, "y": 225, "w": 160, "h": 90, "style": "sub",
         "lines": ["Ollama", "qwen3:4b-instruct-2507"]},

        {"x": 40, "y": 414, "w": 1140, "h": 48, "style": "ink",
         "lines": ["restricted-v2 on every pod, no fixed UID; namespace-wide STRICT mTLS with a workload PERMISSIVE PeerAuthentication on orders, payments-facade and shipping-track only"]},
    ],
    edges=[
        {"x1": 130, "y1": 100, "x2": 130, "y2": 182, "label": "Route"},
        {"x1": 335, "y1": 100, "x2": 335, "y2": 182, "label": "Route"},
        {"x1": 335, "y1": 250, "x2": 335, "y2": 272, "label": "gRPC 9000"},
        {"x1": 440, "y1": 300, "x2": 816, "y2": 290, "label": "writes payments db"},
        {"x1": 565, "y1": 100, "x2": 901, "y2": 150, "amber": True, "label": "produce (stand-in)", "ly": -6},
        {"x1": 901, "y1": 210, "x2": 555, "y2": 272, "label": "consume"},
        {"x1": 555, "y1": 340, "x2": 816, "y2": 320, "label": "writes shipment_prep"},
        {"x1": 795, "y1": 100, "x2": 555, "y2": 182, "label": "Route"},
        {"x1": 555, "y1": 250, "x2": 900, "y2": 225, "label": "reads shipping_track"},
        {"x1": 135, "y1": 250, "x2": 900, "y2": 240, "label": "reads orders db"},
        {"x1": 1055, "y1": 100, "x2": 1080, "y2": 150, "amber": True, "label": "Route", "ly": -6},
        {"x1": 555, "y1": 320, "x2": 1000, "y2": 270, "label": "carrier decision"},
    ],
    notes=[
        {"x": 600, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "OpenShift Local: the same data mesh, an OpenShift-native edge and build path"},
        {"x": 600, "y": 500, "anchor": "middle", "size": 11, "color": "#555555",
         "text": "Same code, same env contract; only examples/_openshift/ differs: S2I builds, Routes, OLM operators, STRICT mTLS with a per-Route exception."},
    ],
)
