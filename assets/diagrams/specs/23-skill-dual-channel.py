"""SKILL.md with frontmatter plus references/ and scripts/ is read directly from the repo as a
project skill, and the same directory is packaged by package-skill.sh into a .skill zip bundle
for distribution elsewhere; order-status.sh checks tools/list before calling an operation and
refuses one absent from it."""

from generate_diagram import emit

emit(
    "23-skill-dual-channel",
    1060,
    500,
    bands=[
        {"x": 30, "y": 40, "w": 1000, "h": 150, "fill": "#fafafa",
         "label": "skills/apa-order-ops/ (one source directory)"},
        {"x": 60, "y": 220, "w": 460, "h": 150, "fill": "#fafafa",
         "label": "Project skill channel"},
        {"x": 570, "y": 220, "w": 460, "h": 150, "fill": "#fafafa",
         "label": "Packaged channel"},
    ],
    nodes=[
        {"x": 60, "y": 80, "w": 260, "h": 50, "style": "accent",
         "lines": ["SKILL.md", "frontmatter: name, description"]},
        {"x": 340, "y": 80, "w": 260, "h": 50, "style": "box",
         "lines": ["references/", "order-operations.md, troubleshooting.md"]},
        {"x": 620, "y": 80, "w": 260, "h": 50, "style": "box",
         "lines": ["scripts/", "order-status.sh"]},
        {"x": 80, "y": 260, "w": 420, "h": 46, "style": "accent",
         "lines": ["read directly by a Claude Code session", "started anywhere under the repo"]},
        {"x": 590, "y": 260, "w": 420, "h": 46, "style": "accent",
         "lines": ["package-skill.sh zips the same directory", "into apa-order-ops.skill"]},
        {"x": 590, "y": 320, "w": 420, "h": 40, "style": "sub",
         "lines": ["single top-level entry named for the skill"]},
        {"x": 30, "y": 400, "w": 1000, "h": 80, "style": "ink",
         "lines": ["order-status.sh checks the live tools/list before calling an operation.",
                   "orderStatus for a seeded order returns SHIPPED / FL-778821; deleteOrder, absent from tools/list,",
                   "is refused, not fabricated, by the same script an agent actually runs."]},
    ],
    edges=[
        {"x1": 190, "y1": 130, "x2": 190, "y2": 260, "amber": True},
        {"x1": 800, "y1": 130, "x2": 800, "y2": 260, "amber": True},
        {"x1": 800, "y1": 306, "x2": 800, "y2": 320},
    ],
    notes=[
        {"x": 530, "y": 26, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "One skill directory, two distribution channels, one script that refuses to fabricate"},
    ],
)
