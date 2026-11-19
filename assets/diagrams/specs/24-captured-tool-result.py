"""The model's own retelling of a remote tool call is nondeterministic and unverifiable; the
durable record instead comes from the framework's captured tool-execution result, read and
parsed server side before any model prose touches it, and the recorded tracking number is a
value the model has no way to produce except by actually reaching the remote tool."""

from generate_diagram import emit

emit(
    "24-captured-tool-result",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa", "label": "Reading the model's own prose (not used here)"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa", "label": "Reading the captured tool result"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 400, "h": 50, "style": "ghost",
         "lines": ["agent.consume(...) returns a sentence", "describing what it says the tool returned"]},
        {"x": 60, "y": 160, "w": 400, "h": 50, "style": "sub",
         "lines": ["nondeterministic: can compress, misstate", "or omit a field the tool actually returned"]},
        {"x": 600, "y": 80, "w": 400, "h": 46, "style": "accent",
         "lines": ["OrderStatusConsumerAgent.consume", "@McpToolBox(\"apa-orders\"), returns Result<String>"]},
        {"x": 600, "y": 150, "w": 400, "h": 46, "style": "box",
         "lines": ["remote orderStatus tool", "a packaged MCP tool server, over Streamable HTTP"]},
        {"x": 600, "y": 220, "w": 400, "h": 46, "style": "box",
         "lines": ["Result.toolExecutions()", "the exact text the remote tool returned, captured"]},
        {"x": 600, "y": 290, "w": 400, "h": 50, "style": "accent",
         "lines": ["OrderStatusRecorder.recordConsultation", "parses status + tracking number, writes the row"]},
        {"x": 30, "y": 390, "w": 1000, "h": 50, "style": "ink",
         "lines": ["A broken connection to the remote tool leaves the row without the seeded tracking number, whether the model writes nothing or invents a plausible reply."]},
    ],
    edges=[
        {"x1": 260, "y1": 130, "x2": 260, "y2": 160},
        {"x1": 800, "y1": 126, "x2": 800, "y2": 150, "amber": True},
        {"x1": 800, "y1": 196, "x2": 800, "y2": 220, "amber": True},
        {"x1": 800, "y1": 266, "x2": 800, "y2": 290, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "The durable record comes from the captured tool result, never the model's retelling of it"},
    ],
)
