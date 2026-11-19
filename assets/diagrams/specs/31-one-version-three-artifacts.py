"""One Quarkus build compiles the pom version in once as quarkus.application.version; the MCP
handshake over Streamable HTTP, the same handshake over stdio (docker run -i against the
identical image), and the served /llms.txt all read that one property, so the three artifacts
report the same version by construction rather than by three maintainers staying in sync."""

from generate_diagram import emit

emit(
    "31-one-version-three-artifacts",
    1060,
    440,
    bands=[],
    nodes=[
        {"x": 330, "y": 30, "w": 400, "h": 64, "style": "accent",
         "lines": ["one Quarkus build", "pom version compiled in as quarkus.application.version"]},
        {"x": 40, "y": 160, "w": 300, "h": 70, "style": "box",
         "lines": ["Streamable HTTP", "initialize: serverInfo.version"]},
        {"x": 380, "y": 160, "w": 300, "h": 70, "style": "box",
         "lines": ["stdio, docker run -i", "initialize: serverInfo.version"]},
        {"x": 720, "y": 160, "w": 300, "h": 70, "style": "box",
         "lines": ["/llms.txt", "Version: line"]},
        {"x": 40, "y": 280, "w": 980, "h": 44, "style": "sub",
         "lines": ["same image, same jar, same property: all three report one version"]},
        {"x": 30, "y": 350, "w": 1000, "h": 70, "style": "ink",
         "lines": ["Overriding QUARKUS_MCP_SERVER_SERVER_INFO_VERSION on one stdio invocation reports a",
                    "different version for that call only; the HTTP handshake and llms.txt still agree with the build, so the three-way match catches exactly that one invocation."]},
    ],
    edges=[
        {"x1": 480, "y1": 94, "x2": 190, "y2": 160},
        {"x1": 530, "y1": 94, "x2": 530, "y2": 160},
        {"x1": 580, "y1": 94, "x2": 870, "y2": 160},
    ],
    notes=[
        {"x": 530, "y": 16, "anchor": "middle", "bold": True, "size": 15, "color": "#444444",
         "text": "One versioned build, three artifacts that can never report a different version by accident"},
    ],
)
