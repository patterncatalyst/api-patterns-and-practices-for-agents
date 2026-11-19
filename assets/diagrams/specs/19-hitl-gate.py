"""The gate timeline (trigger to pause to decision to resume) above a comparison of four
mechanisms for holding an agent action for a human: MCP Elicitation, @HumanInTheLoop, Quarkus
Flow's emitJson/listen, and this chapter's Kafka pending-decision gate."""

from generate_diagram import emit

emit(
    "19-hitl-gate",
    1180,
    700,
    bands=[
        {"x": 20, "y": 40, "w": 1140, "h": 200, "fill": "#fafafa", "label": "The gate timeline"},
        {"x": 20, "y": 270, "w": 1140, "h": 400, "fill": "#fafafa", "label": "Three other mechanisms for the same pattern"},
    ],
    nodes=[
        # timeline
        {"x": 50, "y": 80, "w": 190, "h": 50, "style": "box", "lines": ["trigger", "cancel-request"]},
        {"x": 270, "y": 80, "w": 190, "h": 50, "style": "box", "lines": ["agent classifies", "reasonCode"]},
        {"x": 490, "y": 80, "w": 230, "h": 50, "style": "accent", "lines": ["tool opens the gate", "PENDING row, pending-decision event"]},
        {"x": 750, "y": 80, "w": 150, "h": 50, "style": "ghost", "lines": ["waits", "no side effect yet"]},
        {"x": 930, "y": 50, "w": 190, "h": 46, "style": "accent", "lines": ["APPROVE", "executes exactly once"]},
        {"x": 930, "y": 110, "w": 190, "h": 46, "style": "box", "lines": ["REJECT", "terminal, no side effect"]},
        {"x": 50, "y": 180, "w": 1070, "h": 30, "style": "sub",
         "lines": ["both rows and both topic offsets stay unchanged until a decision arrives"]},
        # table header row
        {"x": 40, "y": 310, "w": 230, "h": 40, "style": "ink", "lines": ["Mechanism"]},
        {"x": 280, "y": 310, "w": 210, "h": 40, "style": "ink", "lines": ["Wait duration"]},
        {"x": 500, "y": 310, "w": 230, "h": 40, "style": "ink", "lines": ["Durable across restart"]},
        {"x": 740, "y": 310, "w": 220, "h": 40, "style": "ink", "lines": ["In-session or out-of-band"]},
        {"x": 970, "y": 310, "w": 190, "h": 40, "style": "ink", "lines": ["State held by"]},
        # row 1: MCP Elicitation
        {"x": 40, "y": 360, "w": 230, "h": 50, "style": "box", "lines": ["MCP Elicitation"]},
        {"x": 280, "y": 360, "w": 210, "h": 50, "style": "sub", "lines": ["seconds, one turn"]},
        {"x": 500, "y": 360, "w": 230, "h": 50, "style": "sub", "lines": ["no, lost on disconnect"]},
        {"x": 740, "y": 360, "w": 220, "h": 50, "style": "sub", "lines": ["in-session"]},
        {"x": 970, "y": 360, "w": 190, "h": 50, "style": "sub", "lines": ["the open MCP connection"]},
        # row 2: @HumanInTheLoop
        {"x": 40, "y": 420, "w": 230, "h": 50, "style": "box", "lines": ["@HumanInTheLoop"]},
        {"x": 280, "y": 420, "w": 210, "h": 50, "style": "sub", "lines": ["minutes, one process"]},
        {"x": 500, "y": 420, "w": 230, "h": 50, "style": "sub", "lines": ["no, in-process only"]},
        {"x": 740, "y": 420, "w": 220, "h": 50, "style": "sub", "lines": ["in-session"]},
        {"x": 970, "y": 420, "w": 190, "h": 50, "style": "sub", "lines": ["the suspended agentic workflow"]},
        # row 3: Quarkus Flow
        {"x": 40, "y": 480, "w": 230, "h": 50, "style": "box", "lines": ["Quarkus Flow emitJson/listen"]},
        {"x": 280, "y": 480, "w": 210, "h": 50, "style": "sub", "lines": ["hours, across restarts"]},
        {"x": 500, "y": 480, "w": 230, "h": 50, "style": "sub", "lines": ["yes, by flowinstanceid"]},
        {"x": 740, "y": 480, "w": 220, "h": 50, "style": "sub", "lines": ["out-of-band"]},
        {"x": 970, "y": 480, "w": 190, "h": 50, "style": "sub", "lines": ["the flow engine's own store"]},
        # row 4: this chapter
        {"x": 40, "y": 540, "w": 230, "h": 56, "style": "accent", "lines": ["Kafka pending-decision gate", "(this chapter)"]},
        {"x": 280, "y": 540, "w": 210, "h": 56, "style": "accent", "lines": ["days, no deadline forced"]},
        {"x": 500, "y": 540, "w": 230, "h": 56, "style": "accent", "lines": ["yes, the gate row and topic"]},
        {"x": 740, "y": 540, "w": 220, "h": 56, "style": "accent", "lines": ["out-of-band"]},
        {"x": 970, "y": 540, "w": 190, "h": 56, "style": "accent", "lines": ["the PostgreSQL gate row"]},
        {"x": 40, "y": 615, "w": 1120, "h": 40, "style": "ink",
         "lines": ["A redelivered APPROVE claims a PENDING row once; a second delivery finds it already terminal and performs no side effect."]},
    ],
    edges=[
        {"x1": 240, "y1": 105, "x2": 270, "y2": 105},
        {"x1": 460, "y1": 105, "x2": 490, "y2": 105, "amber": True},
        {"x1": 720, "y1": 105, "x2": 750, "y2": 105, "amber": True},
        {"x1": 900, "y1": 95, "x2": 930, "y2": 73},
        {"x1": 900, "y1": 115, "x2": 930, "y2": 133},
    ],
    notes=[
        {"x": 590, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Figure 19.1: a Kafka gate durably outlasts a restart, at the cost of answering out-of-band"},
    ],
)
