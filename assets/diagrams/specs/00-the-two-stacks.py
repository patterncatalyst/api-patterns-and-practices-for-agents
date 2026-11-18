"""The two compose stacks: the application stack a request and a tool call travel
through, and the separate observability stack that receives the OTLP path."""

from generate_diagram import emit

emit(
    "00-the-two-stacks",
    1060,
    460,
    bands=[
        {"x": 165, "y": 40, "w": 870, "h": 300, "fill": "#fafafa", "label": "Docker network apa (compose.yaml)"},
    ],
    nodes=[
        {"x": 15, "y": 150, "w": 135, "h": 70, "style": "user",
         "lines": ["Claude Code", "curl, langchain4j"]},
        {"x": 400, "y": 70, "w": 190, "h": 64, "style": "accent",
         "lines": ["Quarkus + Camel service", "8080"]},
        {"x": 190, "y": 190, "w": 150, "h": 64, "style": "box",
         "lines": ["Kafka (KRaft)", "9092 host, kafka:9094"]},
        {"x": 360, "y": 190, "w": 150, "h": 64, "style": "box",
         "lines": ["PostgreSQL", "5432, CNPG operand"]},
        {"x": 530, "y": 190, "w": 150, "h": 64, "style": "sub",
         "lines": ["Apicurio Registry", "8081, health 19000"]},
        {"x": 700, "y": 190, "w": 150, "h": 64, "style": "box",
         "lines": ["Keycloak", "8180, realm apa"]},
        {"x": 870, "y": 190, "w": 150, "h": 64, "style": "box",
         "lines": ["Ollama", "11434, model pull helper"]},
        {"x": 190, "y": 300, "w": 830, "h": 60, "style": "ink",
         "lines": ["Observability stack (compose.lgtm.yaml), started and stopped on its own",
                   "OTLP 4317 and 4318, Grafana 3000, Tempo 3200, Loki 3100, Prometheus 19090"]},
    ],
    edges=[
        {"x1": 150, "y1": 175, "x2": 400, "y2": 110, "label": "calls the endpoint or tool", "ly": -10},
        {"x1": 460, "y1": 134, "x2": 265, "y2": 190, "label": "produces, consumes"},
        {"x1": 490, "y1": 134, "x2": 435, "y2": 190},
        {"x1": 540, "y1": 134, "x2": 605, "y2": 190, "label": "schema", "lx": 30},
        {"x1": 570, "y1": 134, "x2": 775, "y2": 190, "label": "token", "lx": -10, "ly": 14},
        {"x1": 590, "y1": 134, "x2": 945, "y2": 190, "label": "tool call", "lx": 10, "ly": -4},
        {"x1": 495, "y1": 134, "x2": 495, "y2": 300, "amber": True,
         "label": "OTLP: traces, metrics, logs", "lx": 110, "ly": 0},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Two stacks, started and stopped independently"},
        {"x": 530, "y": 440, "anchor": "middle", "size": 11, "color": "#555555",
         "text": "Every example runs on the application stack alone; the observability stack is optional and never required to pass a check."},
    ],
)
