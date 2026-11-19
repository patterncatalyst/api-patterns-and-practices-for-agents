"""An agent proposes a refund; DecisionRuleReviewer evaluates it against the committed rule's
ordered clauses and records APPROVE or REJECT with the failed clause; pointing the same reviewer
at the weakened rule, with the amount-ceiling clause removed, flips the identical violating
proposal from REJECT to APPROVE, and restoring the rule rejects it again."""

from generate_diagram import emit

emit(
    "26-decision-rule-review",
    1060,
    500,
    bands=[
        {"x": 30, "y": 40, "w": 1000, "h": 150, "fill": "#fafafa",
         "label": "POST /review: proposal + order facts"},
        {"x": 60, "y": 220, "w": 460, "h": 180, "fill": "#fafafa",
         "label": "Real rule: contracts/26-refund-review-rule.json"},
        {"x": 570, "y": 220, "w": 460, "h": 180, "fill": "#fafafa",
         "label": "Weakened rule (witness only)"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 460, "h": 50, "style": "box",
         "lines": ["RefundProposalRun", "refundAmount, reason, orderStatus, orderPaidAmount"]},
        {"x": 560, "y": 80, "w": 460, "h": 50, "style": "accent",
         "lines": ["DecisionRuleReviewer.evaluate", "plain switch, no model, no rule engine"]},
        {"x": 80, "y": 260, "w": 420, "h": 46, "style": "box",
         "lines": ["amount-exceeds-paid, order-not-refundable,", "reason-not-allowed, in order"]},
        {"x": 80, "y": 320, "w": 420, "h": 46, "style": "accent",
         "lines": ["violating proposal: REJECT", "failedClause = amount-exceeds-paid"]},
        {"x": 590, "y": 260, "w": 420, "h": 46, "style": "ghost",
         "lines": ["order-not-refundable, reason-not-allowed only", "amount-ceiling clause removed"]},
        {"x": 590, "y": 320, "w": 420, "h": 46, "style": "accent",
         "lines": ["SAME violating proposal: APPROVE", "wrongly, since no clause catches it"]},
        {"x": 30, "y": 420, "w": 1000, "h": 60, "style": "ink",
         "lines": ["ReviewRecorder writes an append-only review.reviews row for every verdict.",
                   "Restoring the real rule path rejects the identical proposal again, naming the same clause."]},
    ],
    edges=[
        {"x1": 290, "y1": 130, "x2": 290, "y2": 170},
        {"x1": 290, "y1": 170, "x2": 790, "y2": 170},
        {"x1": 790, "y1": 130, "x2": 790, "y2": 170, "amber": True},
        {"x1": 290, "y1": 306, "x2": 290, "y2": 320, "amber": True},
        {"x1": 800, "y1": 306, "x2": 800, "y2": 320, "amber": True},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "The proposal varies; the committed rule and its verdict do not"},
    ],
)
