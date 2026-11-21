"""Streaming comparison: where each transport for an agent-facing surface sits on two axes,
how the caller gets data (pull on demand versus a server pushing continuously) and how long
that data survives (ephemeral versus a durable, replayable log). A raw bidirectional stream
and a raw server-to-server callback both point toward the one shape an agent should actually
receive: an MCP tool call or a durable event to react to, reshaped at a facade rather than
handed through unchanged."""

from generate_diagram import emit

emit(
    "ac-streaming-comparison",
    1100,
    660,
    bands=[
        {"x": 150, "y": 50, "w": 900, "h": 480, "fill": "#fafafa"},
    ],
    nodes=[
        {"x": 190, "y": 440, "w": 230, "h": 70, "style": "user",
         "lines": ["REST / MCP tool call", "request, response, ephemeral"]},
        {"x": 460, "y": 440, "w": 230, "h": 70, "style": "box",
         "lines": ["Webhook callback", "one server push, ephemeral"]},
        {"x": 460, "y": 265, "w": 250, "h": 70, "style": "accent",
         "lines": ["SSE / MCP Streamable HTTP", "server push, resumable stream"]},
        {"x": 760, "y": 440, "w": 240, "h": 70, "style": "box",
         "lines": ["WebSockets / gRPC streaming", "bidirectional, continuous, ephemeral"]},
        {"x": 700, "y": 90, "w": 300, "h": 80, "style": "accent",
         "lines": ["Kafka: durable event log", "continuous, durable, replayable, fan-out"]},
        {"x": 170, "y": 560, "w": 860, "h": 70, "style": "ink",
         "lines": ["Reshape, not wrap",
                   "an agent gets an MCP tool call or a durable event to react to, "
                   "never a raw socket or callback it must manage"]},
    ],
    edges=[
        {"x1": 170, "y1": 530, "x2": 1030, "y2": 530},
        {"x1": 170, "y1": 530, "x2": 170, "y2": 50},
        {"x1": 880, "y1": 510, "x2": 760, "y2": 560, "amber": True, "dashed": True,
         "label": "facade reshapes", "lx": 0, "ly": 10},
        {"x1": 575, "y1": 510, "x2": 460, "y2": 560, "amber": True, "dashed": True,
         "label": "becomes event-triggered wakeup", "lx": 0, "ly": 10},
    ],
    notes=[
        {"x": 600, "y": 25, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Decision quadrant: transports for an agent-facing surface"},
        {"x": 170, "y": 555, "anchor": "start", "size": 11, "color": "#888888",
         "text": "Caller pulls on demand"},
        {"x": 1030, "y": 555, "anchor": "end", "size": 11, "color": "#888888",
         "text": "Server pushes continuously"},
        {"x": 160, "y": 525, "anchor": "end", "size": 11, "color": "#888888",
         "text": "Ephemeral, no replay"},
        {"x": 160, "y": 60, "anchor": "end", "size": 11, "color": "#888888",
         "text": "Durable, replayable"},
    ],
)
