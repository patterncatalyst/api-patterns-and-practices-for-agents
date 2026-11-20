"""Deploying a reused service onto the shared Kubernetes platform: the same image that runs on
compose now reads the platform's env contract, reaches Strimzi Kafka, CloudNativePG Postgres and
in-cluster Ollama by DNS, and every host-facing check goes through a platform NodePort or
kubectl exec, never a forward or a tunnel."""

from generate_diagram import emit

emit(
    "37-deploying-onto-the-platform",
    1080,
    520,
    bands=[
        {"x": 30, "y": 15, "w": 1020, "h": 95, "fill": "#fafafa",
         "label": "Host 127.0.0.1 (verify.sh): NodePorts only, no forwarding"},
        {"x": 30, "y": 135, "w": 330, "h": 150, "fill": "#fafafa",
         "label": "apa namespace (Istio sidecar, STRICT mTLS)"},
        {"x": 400, "y": 135, "w": 380, "h": 310, "fill": "#fafafa", "label": "apa-data namespace"},
        {"x": 820, "y": 135, "w": 230, "h": 150, "fill": "#fafafa", "label": "observability namespace"},
    ],
    nodes=[
        {"x": 50, "y": 45, "w": 150, "h": 55, "style": "user",
         "lines": ["kcat -P", "produces to the Kafka NodePort"]},
        {"x": 220, "y": 45, "w": 190, "h": 55, "style": "user",
         "lines": ["kubectl exec ... psql", "asserts the shipment_prep row"]},
        {"x": 430, "y": 45, "w": 240, "h": 55, "style": "user",
         "lines": ["curl Tempo, Prometheus, Loki", "over their own NodePorts"]},
        {"x": 690, "y": 45, "w": 220, "h": 55, "style": "user",
         "lines": ["kubectl set env + rollout", "the biting negative witness"]},
        {"x": 55, "y": 180, "w": 280, "h": 90, "style": "accent",
         "lines": ["event-triggered-agent-wakeup", "Deployment + Service, ClusterIP",
                   "the same image the compose example builds"]},
        {"x": 420, "y": 175, "w": 160, "h": 70, "style": "box",
         "lines": ["Kafka (Strimzi)", "apa-kafka-bootstrap:9092"]},
        {"x": 600, "y": 175, "w": 160, "h": 70, "style": "box",
         "lines": ["PostgreSQL (CloudNativePG)", "apa-postgres-rw:5432"]},
        {"x": 510, "y": 280, "w": 160, "h": 70, "style": "box",
         "lines": ["Ollama", "in-cluster only, 11434"]},
        {"x": 850, "y": 180, "w": 170, "h": 80, "style": "sub",
         "lines": ["otel-lgtm", "Tempo, Prometheus, Loki, Grafana"]},
    ],
    edges=[
        {"x1": 125, "y1": 100, "x2": 480, "y2": 175, "label": "produce (NodePort 9092)"},
        {"x1": 480, "y1": 245, "x2": 195, "y2": 180, "label": "consume"},
        {"x1": 335, "y1": 225, "x2": 600, "y2": 210, "label": "writes shipment_prep"},
        {"x1": 195, "y1": 270, "x2": 560, "y2": 280, "label": "tool call decision"},
        {"x1": 335, "y1": 200, "x2": 935, "y2": 180, "amber": True,
         "label": "OTLP traces, metrics, logs", "ly": -6},
        {"x1": 315, "y1": 100, "x2": 660, "y2": 175, "label": "assert (kubectl exec)"},
        {"x1": 550, "y1": 100, "x2": 935, "y2": 180, "label": "assert signals"},
        {"x1": 800, "y1": 100, "x2": 195, "y2": 180, "label": "disable, restore"},
    ],
    notes=[
        {"x": 540, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Deploying a reused service onto the shared Kubernetes platform"},
        {"x": 540, "y": 505, "anchor": "middle", "size": 11, "color": "#555555",
         "text": "Every host-facing check reaches a platform NodePort or uses kubectl exec; nothing forwards, tunnels or proxies."},
    ],
)
