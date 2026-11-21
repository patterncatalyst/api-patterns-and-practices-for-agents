"""The lever map for typed, consistent agent I/O: five places a system can constrain a model's
output, from the strongest guarantee (the grammar itself cannot emit the wrong shape) to the
weakest (a lower temperature only narrows token-level variance, it never bounds the shape). Each
row names the book's own pattern that already types that boundary, where one exists."""

from generate_diagram import emit

ROW_H = 92
ROW_GAP = 16
TOP = 70
LEFT = 180
ROW_W = 760

def row_y(i):
    return TOP + i * (ROW_H + ROW_GAP)

emit(
    "ag-structured-outputs",
    1040,
    660,
    bands=[
        {"x": 30, "y": 20, "w": 980, "h": row_y(5) - 20, "fill": "#fafafa",
         "label": "Strongest (top) to weakest (bottom): what actually bounds the model's output"},
    ],
    nodes=[
        {"x": LEFT, "y": row_y(0), "w": ROW_W, "h": ROW_H, "style": "accent",
         "lines": ["1. Type the output: Ollama format + JSON Schema, grammar-constrained decoding",
                    "the model cannot emit a token sequence outside the schema's shape",
                    "this appendix: ExtractedIntent via TypedIntentExtractor"]},
        {"x": LEFT, "y": row_y(1), "w": ROW_W, "h": ROW_H, "style": "box",
         "lines": ["2. Type the tool surface: capture the tool's own structured result",
                    "the model's prose is never the record of what happened",
                    "the captured-tool-result-not-prose pattern"]},
        {"x": LEFT, "y": row_y(2), "w": ROW_W, "h": ROW_H, "style": "box",
         "lines": ["3. Validate and repair: check a produced output against a fixed rule",
                    "catches a wrong decision after the fact, never prevents emitting one",
                    "the deterministic decision-rule review; the deterministic eval replay"]},
        {"x": LEFT, "y": row_y(3), "w": ROW_W, "h": ROW_H, "style": "box",
         "lines": ["4. Constrain the input: reject a malformed request before it is processed",
                    "narrows what reaches the model, not what the model can answer",
                    "the JSON-schema ingest gate"]},
        {"x": LEFT, "y": row_y(4), "w": ROW_W, "h": ROW_H, "style": "ghost",
         "lines": ["5. Seed and temperature: lower token-level variance",
                    "narrows which tokens are likely, never which shape is allowed",
                    "variance reduction, not a consistency guarantee"]},
    ],
    edges=[
        {"x1": 90, "y1": row_y(0) + ROW_H // 2, "x2": 90, "y2": row_y(4) + ROW_H // 2,
         "amber": True, "label": "stronger to weaker"},
    ],
    notes=[
        {"x": 520, "y": row_y(5) + 38, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "Consistency is engineered at the typed boundary, never at the token"},
        {"x": 520, "y": row_y(5) + 60, "anchor": "middle", "size": 11, "color": "#555555",
         "text": "The same intent yields the same output shape and semantic class on every call because a lever"
                 " at the top of this map makes the wrong shape unreachable, not because the model behaves."},
    ],
)
