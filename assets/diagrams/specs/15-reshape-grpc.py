"""A separate gRPC payment-service image sits behind an MCP tool facade; the facade reshapes a
decimal amount to int64 cents on the way in and back to decimal on the way out, and turns a gRPC
status into an instructive tool error instead of leaking the protocol across."""

from generate_diagram import emit

emit(
    "15-reshape-grpc",
    1060,
    460,
    bands=[
        {"x": 30, "y": 40, "w": 460, "h": 330, "fill": "#fafafa", "label": "mcp-facade image, MCP tool surface"},
        {"x": 570, "y": 40, "w": 460, "h": 330, "fill": "#fafafa", "label": "payment-service image, gRPC only"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 400, "h": 40, "style": "box", "lines": ['takePayment(orderId, amount="49.99")']},
        {"x": 60, "y": 136, "w": 400, "h": 46, "style": "accent", "lines": ["AmountReshape.toCents", "decimal \"49.99\" to int64 4999"]},
        {"x": 60, "y": 270, "w": 400, "h": 46, "style": "accent", "lines": ["AmountReshape.toView", "int64 cents back to decimal, status text"]},
        {"x": 60, "y": 330, "w": 400, "h": 40, "style": "sub", "lines": ["agent never sees Protobuf, cents or a gRPC status"]},
        {"x": 600, "y": 80, "w": 400, "h": 40, "style": "user", "lines": ["PaymentService.takePayment, over a real gRPC channel"]},
        {"x": 600, "y": 136, "w": 400, "h": 40, "style": "box", "lines": ["payments.payments, amounts stored as cents"]},
        {"x": 600, "y": 270, "w": 400, "h": 46, "style": "ghost", "lines": ["StatusRuntimeException", "NOT_FOUND, FAILED_PRECONDITION, INVALID_ARGUMENT"]},
        {"x": 30, "y": 390, "w": 1000, "h": 56, "style": "ink",
         "lines": ["Skipping the reshape (treating the decimal string as if it were already cents) turns a $49.99 payment into $0.49;",
                   "a gRPC status left unwrapped reaches the agent as a protocol error instead of an instructive tool error"]},
    ],
    edges=[
        {"x1": 260, "y1": 120, "x2": 260, "y2": 136},
        {"x1": 460, "y1": 159, "x2": 600, "y2": 100, "amber": True, "label": "gRPC"},
        {"x1": 800, "y1": 176, "x2": 800, "y2": 270},
        {"x1": 600, "y1": 293, "x2": 460, "y2": 293, "amber": True, "label": "reshape or wrap"},
        {"x1": 260, "y1": 182, "x2": 260, "y2": 270},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Reshape at the boundary, never wrap a heterogeneous protocol through unchanged"},
    ],
)
