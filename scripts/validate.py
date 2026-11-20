#!/usr/bin/env python3
"""Static content checks for the book. Python 3, PyYAML only.

    python3 scripts/validate.py              check the tree, exit 1 on any failure
    python3 scripts/validate.py --self-test  plant defects in a temp tree and assert each is caught

Directories may be empty or missing; checks that have nothing to look at pass.
This is the r01 skeleton's validator: it carries the checks that matter with
zero chapters and zero examples (front matter, Liquid, diagrams, dashes, the
stand-alone-chapter rule, the cross-stack callout shape, and the example
contract), adapted from the event-driven-patterns-with-quarkus-and-camel
sibling. Appendix- and Kubernetes-specific branches grow here as those land.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import xml.dom.minidom
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
import host_ids  # noqa: E402  (rules read host values at run time; none are stored)

MAX_WORDS = 2500
MAX_BRO_WORDS = 250  # soft cap (warning)
MAX_BRO_BULLETS = 3
BRO_HEAD = "## Build, run, observe"
CHECKNUM_RE = re.compile(r"\bchecks? \d+|\b\d+ of \d+ runs\b|\bin \d+ runs\b", re.I)
CALLOUT = "Explore with your agent"
FENCE = re.compile(r"^\s*(`{3,}|~{3,})")
RAW_RE = re.compile(r"\{%-?\s*(raw|endraw)\s*-?%\}")
INCLUDE_RE = re.compile(r"\{%-?\s*include\s+excalidraw\.html\s+(.*?)-?%\}", re.S)
FIG_PARAM = re.compile(r"""\b(?:file|name)\s*=\s*["']([^"']+)["']""")
LIQUID_OK = re.compile(
    r"""\{\{-?\s*(?:(?:site|page|include|forloop|content|part|part_docs|this_part|doc|prev_\w+|next_\w+)\b
      |['"][^'"]*['"]\s*\|)""", re.X)

DASH_RE = re.compile("[—–]")
VERSION_RE = re.compile(r"(?<![\d.])\d+\.\d+\.\d+(?!\.?\d)")
URL_RE = re.compile(r"https?://\S+")
TESTNAME_RE = re.compile(r"@Test\b|\b[A-Z]\w*[a-z0-9](?:Test|IT)\b|\bshould[A-Z]\w+")
# Owner rule: every page stands alone. No reference to another chapter, appendix or part of this
# book in prose (back or forward); related pages are linked only under `## References`. Appendix D
# (the pattern map and cross-reference) is exempt. Chapter numbers of other books, cited as sources,
# are allowed.
XREF_RE = re.compile(r"\b(chapters?|appendix|appendices|parts?)\s+(\d+|[A-E])\b|\]\(\{\{\s*'/docs/|\]\(/docs/|\]\(\.\./docs/", re.I)
XREF_WORD_RE = re.compile(
    r"\b(?:the\s+)?(?:previous|preceding|prior|next|following|earlier|later|last|first)\s+(?:chapters?|appendix|appendices|parts?)\b"
    r"|\bthe\s+(?!same\b)[\w-]+(?:\s+[\w-]+)?\s+(?:chapter|appendix)\b"
    r"|\b(?:chapters|parts|appendices)\s+(?:\d+|[A-E]|one|two|three|four|five|six|seven|eight)\b"
    r"|\bparts?\s+(?:one|two|three|four|five|six|seven|eight)\b"
    r"|\bexamples?\s+\d{2}\b|\b(?:these|those|the\s+earlier|the\s+other)\s+examples\b", re.I)
CHREF_RE = re.compile(r"\bch\.?\s?\d{1,2}\b", re.I)  # the abbreviated 'ch13' / 'ch 13' chapter-reference form
ACCOMPANIES_RE = re.compile(r"accompanies \[[^\]]*\]\(\{\{[^)]*\)")
XREF_SOURCE_RE = re.compile(
    r"Geewax|Hohpe|Woolf|Richardson|Amundsen|Ruby|Lauret|Dehghani|"
    r"Enterprise Integration Patterns|API Design Patterns|RESTful Web APIs|Data Mesh|"
    r"\*[A-Z][^*]+\*,? (ch|chapter)", re.I)
SELFREF_RE = re.compile(r"\b(this|the) (chapter|section)\b|\bthe example shows\b|\bwe will\b", re.I)
K8S_SETUP_HEADING = "## Setting up the cluster"
EXAMPLE_DIR_RE = re.compile(r"(\d+|a[a-e])-")

FORWARDING = re.compile(
    r"port-forward|minikube\s+tunnel|\bssh\s+(?:\S+\s+)*-[LRD]\b|ip_forward|\biptables\b|\bnft\b|"
    r"firewall-cmd|sysctl\s+-w|\bsocat\b|\b(?:oc|kubectl)\s+proxy\b", re.I)


def xref_hit(t: str) -> str | None:
    """The first cross-reference in a prose line, or None. A chapter of another book cited as a source is allowed."""
    for rx in (XREF_RE, XREF_WORD_RE, CHREF_RE):
        for x in rx.finditer(t):
            if not XREF_SOURCE_RE.search(t[max(0, x.start() - 90):x.start() + len(x.group(0)) + 40]):
                return x.group(0)
    return None


class Report:
    def __init__(self, root: Path):
        self.root = root
        self.errors: list[str] = []
        self.warnings: list[str] = []

    def err(self, path: Path | str, line: int | None, msg: str) -> None:
        p = path.relative_to(self.root) if isinstance(path, Path) and path.is_absolute() else path
        self.errors.append(f"{p}{f':{line}' if line else ''}: {msg}")

    def warn(self, msg: str) -> None:
        self.warnings.append(msg)


def files(root: Path, *globs: str):
    for g in globs:
        for p in sorted(root.glob(g)):
            if p.is_file() and not any(x in p.relative_to(root).parts for x in ("_site", "target", "vendor", "node_modules")):
                yield p


def line_of(text: str, pos: int) -> int:
    return text.count("\n", 0, pos) + 1


def split_front(text: str):
    """Return (front_matter_text | None, body, number of lines before body)."""
    m = re.match(r"---[ \t]*\n(.*?\n)?---[ \t]*(?:\n|$)", text, re.S)
    if not m:
        return None, text, 0
    return (m.group(1) or ""), text[m.end():], text[:m.end()].count("\n")


def fence_mask(body: str) -> list[bool]:
    """True per line when the line is a fence delimiter or inside a fenced block."""
    out, fence = [], None
    for ln in body.split("\n"):
        m = FENCE.match(ln)
        if fence is None:
            if m:
                fence = m.group(1)[0] * 3
                out.append(True)
            else:
                out.append(False)
        else:
            out.append(True)
            if m and m.group(1)[0] == fence[0] and ln.strip().startswith(fence):
                fence = None
    return out


def prose_lines(body: str) -> list[tuple[int, str]]:
    mask = fence_mask(body)
    return [(i + 1, ln) for i, ln in enumerate(body.split("\n")) if not mask[i]]


def mask_raw(text: str, path: Path, rep: Report) -> str:
    out, start = list(text), None
    for m in RAW_RE.finditer(text):
        if m.group(1) == "raw":
            if start is not None:
                rep.err(path, line_of(text, m.start()), "nested or repeated {% raw %}")
                continue
            start = m.start()
        else:
            if start is None:
                rep.err(path, line_of(text, m.start()), "{% endraw %} without a matching {% raw %}")
                continue
            for i in range(start, m.end()):
                if out[i] != "\n":
                    out[i] = " "
            start = None
    if start is not None:
        rep.err(path, line_of(text, start), "{% raw %} is never closed")
        for i in range(start, len(text)):
            if out[i] != "\n":
                out[i] = " "
    return "".join(out)


# ---- checks ---------------------------------------------------------------

def check_front_matter(rep: Report):
    root = rep.root
    part_names: set[str] = set()
    for p in files(root, "_parts/*.md"):
        fm, _, _ = split_front(p.read_text(encoding="utf-8"))
        d = load_fm(rep, p, fm, required=True)
        if d and d.get("part_name"):
            part_names.add(str(d["part_name"]))
    for p in files(root, "_docs/*.md"):
        fm, _, _ = split_front(p.read_text(encoding="utf-8"))
        d = load_fm(rep, p, fm, required=True)
        if d is None:
            continue
        part = d.get("part")
        if part is None:
            rep.err(p, 1, "front matter has no `part`")
        elif str(part) not in part_names:
            rep.err(p, 1, f"part '{part}' matches no _parts part_name ({', '.join(sorted(part_names)) or 'none'})")
    for p in files(root, "_example_pages/*.md"):
        fm, _, _ = split_front(p.read_text(encoding="utf-8"))
        load_fm(rep, p, fm, required=True)
    for p in files(root, "_plans/*.md"):
        fm, _, _ = split_front(p.read_text(encoding="utf-8"))
        load_fm(rep, p, fm, required=False)


def load_fm(rep: Report, p: Path, fm, required: bool):
    if fm is None:
        if required:
            rep.err(p, 1, "no front matter")
        return None
    try:
        d = yaml.safe_load(fm) if fm.strip() else {}
    except yaml.YAMLError as e:
        rep.err(p, 1, f"front matter does not parse: {str(e).splitlines()[0]}")
        return None
    if not isinstance(d, dict):
        rep.err(p, 1, "front matter is not a mapping")
        return None
    return d


def check_liquid(rep: Report):
    for p in files(rep.root, "_docs/*.md", "_example_pages/*.md"):
        text = p.read_text(encoding="utf-8")
        masked = mask_raw(text, p, rep)
        for m in re.finditer(r"\{\{", masked):
            if not LIQUID_OK.match(masked, m.start()):
                snip = masked[m.start():m.start() + 30].split("\n")[0]
                rep.err(p, line_of(masked, m.start()), f"stray `{{{{` outside {{% raw %}}: {snip!r}")


def check_diagrams(rep: Report):
    root = rep.root
    dg = root / "assets/diagrams"
    for p in files(root, "_docs/*.md", "_example_pages/*.md", "index.html", "chapters.html"):
        text = p.read_text(encoding="utf-8")
        masked = mask_raw(text, p, rep)
        for m in INCLUDE_RE.finditer(masked):
            ln = line_of(masked, m.start())
            fp = FIG_PARAM.search(m.group(1))
            if not fp:
                rep.err(p, ln, "excalidraw include has no file=/name= parameter")
                continue
            name = fp.group(1)
            for rel in (f"{name}.svg", f"{name}.excalidraw", f"specs/{name}.py"):
                if not (dg / rel).is_file():
                    rep.err(p, ln, f"figure '{name}': assets/diagrams/{rel} is missing")
    for spec in files(root, "assets/diagrams/specs/*.py"):
        if spec.name.startswith("_"):
            continue
        if not (dg / f"{spec.stem}.svg").is_file():
            rep.err(spec, None, f"orphan spec: assets/diagrams/{spec.stem}.svg does not exist (run scripts/build-diagrams.sh)")
    for svg in files(root, "assets/diagrams/*.svg"):
        try:
            xml.dom.minidom.parseString(svg.read_bytes())
        except Exception as e:  # noqa: BLE001
            rep.err(svg, None, f"SVG does not parse as XML: {e}")
    for ex in files(root, "assets/diagrams/*.excalidraw"):
        try:
            json.loads(ex.read_text(encoding="utf-8"))
        except ValueError as e:
            rep.err(ex, None, f"excalidraw file is not valid JSON: {e}")


def check_dashes(rep: Report):
    for p in files(rep.root, "_docs/*", "_parts/*", "_example_pages/*", "index.html", "chapters.html",
                   "README.md", "bibliography.md", "examples/**/README.md", "assets/diagrams/*.svg"):
        for i, ln in enumerate(p.read_text(encoding="utf-8", errors="replace").split("\n"), 1):
            if DASH_RE.search(ln):
                rep.err(p, i, "em dash or en dash (use a comma, colon, parentheses or 'to')")


def chapter_words(body: str) -> int:
    body = re.sub(r"<!--.*?-->", " ", body, flags=re.S)
    return len(re.findall(r"[A-Za-z0-9][\w'’-]*", "\n".join(t for _, t in prose_lines(body))))


def check_bro(rep: Report, p: Path, plines, off: int):
    """Build, run, observe stays short: commands, what to watch, at most three Try bullets."""
    sec, start = [], None
    for i, t in plines:
        if t.startswith(BRO_HEAD):
            start = i
            continue
        if start is not None:
            if t.startswith("## "):
                break
            sec.append((i, t))
    if start is None:
        return
    words = len(re.findall(r"[A-Za-z0-9][\w'’-]*", "\n".join(t for _, t in sec)))
    if words > MAX_BRO_WORDS:
        rep.warn(f"{p.relative_to(rep.root)}:{start + off}: '{BRO_HEAD}' has {words} prose words, soft cap {MAX_BRO_WORDS}")
    bullets = [i for i, t in sec if re.match(r"\s*[-*] ", t)]
    if len(bullets) > MAX_BRO_BULLETS:
        rep.warn(f"{p.relative_to(rep.root)}:{start + off}: '{BRO_HEAD}' has {len(bullets)} bullets, soft cap {MAX_BRO_BULLETS}")
    for i, t in sec:
        m = CHECKNUM_RE.search(t)
        if m:
            rep.err(p, i + off, f"check number or run statistic '{m.group(0)}' in '{BRO_HEAD}' (belongs in the README)")


def check_example_pages(rep: Report):
    """The cross-reference rule also covers the example pages (_example_pages/*.md prose)."""
    for p in files(rep.root, "_example_pages/*.md"):
        fm, body, off = split_front(p.read_text(encoding="utf-8"))
        section = ""
        for i, t in prose_lines(re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), body, flags=re.S)):
            if re.match(r"##\s", t):
                section = t.strip()
            if section.lower().startswith("## references"):
                continue
            hit = xref_hit(ACCOMPANIES_RE.sub("accompanies this page", t))
            if hit:
                rep.err(p, i + off, f"cross-reference '{hit}' in example page prose: each page stands alone; "
                                    "link related pages only under ## References")


def check_chapters(rep: Report):
    for p in files(rep.root, "_docs/*.md"):
        text = p.read_text(encoding="utf-8")
        fm, body, off = split_front(text)
        if not p.name.startswith("a"):
            d = re.search(r"^description:\s*(.*)$", text.split("\n---", 2)[0] if text.startswith("---") else "", re.M)
            if d and XREF_RE.search(d.group(1)) and not XREF_SOURCE_RE.search(d.group(1)):
                rep.err(p, None, f"cross-reference '{XREF_RE.search(d.group(1)).group(0)}' in the description")
        n = chapter_words(body)
        if n > MAX_WORDS:
            rep.err(p, None, f"{n} prose words, limit {MAX_WORDS}")
        plines = prose_lines(re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), body, flags=re.S))
        check_bro(rep, p, plines, off)
        has_pattern = any(re.match(r"##\s+Pattern:", t) for _, t in plines)
        callouts = [i for i, t in plines if CALLOUT in t]
        if has_pattern and len(callouts) != 1:
            rep.err(p, None, f"pattern chapter needs exactly one '{CALLOUT}' callout, found {len(callouts)}")
        elif not has_pattern and len(callouts) > 1:
            rep.err(p, None, f"at most one '{CALLOUT}' callout allowed, found {len(callouts)}")
        if len(callouts) == 1:
            check_callout_shape(rep, p, body.split("\n"), callouts[0], off)
        section = ""
        for i, t in plines:
            if re.match(r"##\s", t):
                section = t.strip()
            if not t.lstrip().startswith(">"):
                sr = SELFREF_RE.search(t)
                if sr:
                    rep.err(p, i + off, f"self-reference '{sr.group(0)}' in prose")
            m = TESTNAME_RE.search(t)
            if m:
                rep.err(p, i + off, f"test-name token '{m.group(0)}' in prose")
            if not p.name.startswith("a") and not section.lower().startswith("## references"):
                hit = xref_hit(t)
                if hit:
                    rep.err(p, i + off, f"cross-reference '{hit}' in prose: each page stands alone; "
                                        "link related pages only under ## References")
            # Versions live in chapter 00, the appendices (a*) and a chapter's own "Setting up the cluster" section.
            if not (p.name.startswith(("00-", "a")) or section == K8S_SETUP_HEADING):
                v = VERSION_RE.search(URL_RE.sub("", t))
                if v:
                    rep.err(p, i + off, f"version string '{v.group(0)}' outside chapter 00, the appendices and a "
                                        "'Setting up the cluster' section")


HEADING2_RE = re.compile(r"^##(?!#)\s")
SPINE_ORDER = ["## Learning objectives", "## Why this matters", "## Anti-patterns",
               "## When not to use it", "## References"]


def check_chapter_spine(rep: Report):
    """Every non-appendix chapter carries the spine: '## Learning objectives' (first,
    bulleted), '## Why this matters' (second), ..., '## Anti-patterns' (after
    '## Build, run, observe' when that section exists), '## When not to use it' (pattern
    chapters only), '## References'. Appendices (basename starting with 'a') are exempt:
    they are unbuilt and decided later.
    """
    for p in files(rep.root, "_docs/*.md"):
        if p.name.startswith("a"):
            continue
        _, body, off = split_front(p.read_text(encoding="utf-8"))
        plines = prose_lines(body)
        h2 = [(i, t.rstrip()) for i, t in plines if HEADING2_RE.match(t)]
        if not h2:
            rep.err(p, 1 + off, "chapter spine: no level-2 headings found")
            continue
        lines: dict[str, int] = {}
        for i, t in h2:
            lines.setdefault(t, i)
        has_pattern = any(t.startswith("## Pattern:") for _, t in h2)

        required = ["## Learning objectives", "## Why this matters", "## Anti-patterns", "## References"]
        if has_pattern:
            required = required + ["## When not to use it"]
        for req in required:
            if req not in lines:
                rep.err(p, None, f"chapter spine: missing required heading '{req}'")

        if h2[0][1] != "## Learning objectives":
            rep.err(p, h2[0][0] + off, "chapter spine: '## Learning objectives' must be the first level-2 heading")
        if len(h2) > 1 and h2[1][1] != "## Why this matters":
            rep.err(p, h2[1][0] + off, "chapter spine: '## Why this matters' must be the second level-2 heading")

        if "## Learning objectives" in lines:
            lo = lines["## Learning objectives"]
            nxt = next((t for i, t in plines if i > lo and t.strip() != ""), None)
            if nxt is None or not nxt.lstrip().startswith("- "):
                rep.err(p, lo + off, "chapter spine: '## Learning objectives' must be followed by a bulleted list")

        present = [(lines[h], h) for h in SPINE_ORDER if h in lines]
        for (l1, n1), (l2, n2) in zip(present, present[1:]):
            if l1 >= l2:
                rep.err(p, l2 + off, f"chapter spine: '{n2}' must come after '{n1}'")

        if BRO_HEAD in lines and "## Anti-patterns" in lines and lines["## Anti-patterns"] < lines[BRO_HEAD]:
            rep.err(p, lines["## Anti-patterns"] + off,
                    f"chapter spine: '## Anti-patterns' must come after '{BRO_HEAD}'")


def check_callout_shape(rep: Report, p: Path, lines: list[str], at: int, off: int):
    """The cross-stack callout:
        > **\U0001F916 Explore with your agent**
        > **Intent (stack-neutral):** ...
        > **Contract shape:** ...
        > **Build it here:** "..."
        > **Build it elsewhere:** "..."
        > Check: ...
    """
    i = at - 1
    block = []
    while i < len(lines) and lines[i].lstrip().startswith(">"):
        block.append(lines[i].lstrip())
        i += 1
    ln = at + off
    if not re.match(r">\s*\*\*\U0001F916 Explore with your agent\*\*\s*$", block[0]):
        rep.err(p, ln, "callout heading must be exactly '> **\U0001F916 Explore with your agent**'")
    required = [
        ("Intent (stack-neutral):", r">\s*\*\*Intent \(stack-neutral\):\*\*"),
        ("Contract shape:", r">\s*\*\*Contract shape:\*\*"),
        ("Build it here:", r'>\s*\*\*Build it here:\*\*\s*["\u201c]'),
        ("Build it elsewhere:", r'>\s*\*\*Build it elsewhere:\*\*\s*["\u201c]'),
    ]
    for label, pat in required:
        if not any(re.match(pat, b) for b in block):
            rep.err(p, ln, f"callout is missing the '{label}' line (or it is not quoted where required)")
    checks = [b for b in block if re.match(r">\s*Check:", b)]
    if len(checks) != 1:
        rep.err(p, ln, f"callout needs exactly one 'Check:' line in the same blockquote, found {len(checks)}")
    if any(re.match(r">\s*>", b) for b in block):
        rep.err(p, ln, "callout must not contain a nested quote")


def check_examples(rep: Report):
    ex = rep.root / "examples"
    if not ex.is_dir():
        return
    for d in sorted(ex.iterdir()):
        if not (d.is_dir() and EXAMPLE_DIR_RE.match(d.name)):
            continue
        v = d / "verify.sh"
        if not v.is_file():
            rep.err(v, None, "missing verify.sh")
        else:
            if not os.access(v, os.X_OK):
                rep.err(v, None, "verify.sh is not executable")
            r = subprocess.run(["bash", "-n", str(v)], capture_output=True, text=True)
            if r.returncode:
                rep.err(v, None, f"bash -n failed: {r.stderr.strip()}")
            body = v.read_text(encoding="utf-8")
            if not (re.search(r"down -v --remove-orphans", body)
                    and re.search(r"up -d --build --force-recreate --renew-anon-volumes --wait", body)):
                rep.err(v, None, "verify.sh must start fresh: `down -v --remove-orphans`, then "
                                 "`up -d --build --force-recreate --renew-anon-volumes --wait`")
        if not (d / "README.md").is_file():
            rep.err(d / "README.md", None, "missing README.md")

    IMG_RE = re.compile(r"^\s*-?\s*image:\s*(\S+)", re.M)
    for c in files(rep.root, "examples/**/compose*.yaml", "examples/**/compose*.yml",
                   "examples/_k8s/**/*.yaml", "examples/[0-9]*/**/*.yaml", "examples/a[a-e]-*/**/*.yaml"):
        if "/target/" in c.as_posix():
            continue
        text = c.read_text(encoding="utf-8")
        for m in IMG_RE.finditer(text):
            ref = m.group(1).strip("\"'")
            ln = line_of(text, m.start())
            dm = re.search(r"\$\{[^}:]+:?-([^}]*)\}", ref)
            if "${" in ref:
                if not dm:
                    rep.err(c, ln, f"image '{ref}' has no default to check")
                    continue
                ref = ref.replace(dm.group(0), dm.group(1))
            first = ref.split("/")[0]
            if "/" not in ref or not ("." in first or ":" in first or first == "localhost"):
                rep.err(c, ln, f"image '{ref}' is not fully qualified (registry host missing)")
            tail = ref.rsplit("/", 1)[-1]
            if "@sha256:" in tail:
                continue
            if ":" not in tail:
                rep.err(c, ln, f"image '{ref}' has no tag")
            elif tail.split(":", 1)[1] == "latest":
                rep.err(c, ln, f"image '{ref}' uses :latest")


def check_no_forwarding(rep: Report):
    """No tunnels, port forwarding, IP forwarding or host network changes: publish ports instead."""
    roots = ["_docs/*", "_parts/*", "_example_pages/*", "_includes/*", "_layouts/*", "index.html", "chapters.html",
             "README.md", "bibliography.md", "examples/**/*", ".github/**/*", "scripts/*.sh"]
    for p in files(rep.root, *roots):
        try:
            text = p.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for m in FORWARDING.finditer(text):
            rep.err(p, line_of(text, m.start()),
                    f"forwarding or host network change '{m.group(0)}' is banned: publish ports "
                    "(compose ports, minikube start --ports, NodePorts) instead")


CHECKS = [check_front_matter, check_liquid, check_diagrams, check_dashes, check_chapters,
          check_chapter_spine, check_example_pages, check_examples, check_no_forwarding]


def run(root: Path) -> Report:
    rep = Report(root)
    for c in CHECKS:
        c(rep)
    return rep


# ---- self-test ------------------------------------------------------------

def self_test() -> int:
    good_callout = (
        '> **\U0001F916 Explore with your agent**\n'
        '> **Intent (stack-neutral):** the API must let an agent discover the demo tool.\n'
        '> **Contract shape:** operation intent, inputs, response shape, error shape, no idempotency key needed.\n'
        '> **Build it here:** "Call the demo tool and check the rule."\n'
        '> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI: call the demo endpoint and check the rule."\n'
        '> Check: it holds.\n'
    )
    seg_front = '---\ntitle: "Demo"\norder: 1\npart: demo\n---\n\n'
    seg_lo = '## Learning objectives\n\n- Do the demo thing.\n\n'
    seg_wtm = ('## Why this matters\n\nIntro text.\n\n'
               '{% include excalidraw.html file="01-demo" alt="Demo" %}\n\n')
    seg_pattern = '## Pattern: Demo\n\n' + good_callout + '\n'
    seg_bro = BRO_HEAD + '\n\n```bash\n./verify.sh\n```\n\nIt runs and prints the result.\n\n'
    seg_anti = '## Anti-patterns\n\nDo not do the bad thing.\n\n'
    seg_wntu = '## When not to use it\n\nSkip this pattern when the bad thing is fine.\n\n'
    seg_refs = '## References\n\n- A reference.\n'
    good_doc = seg_front + seg_lo + seg_wtm + seg_pattern + seg_bro + seg_anti + seg_wntu + seg_refs
    results = []
    with tempfile.TemporaryDirectory() as tmp:
        def build(name: str) -> Path:
            r = Path(tmp) / name
            for d in ("_parts", "_docs", "assets/diagrams/specs"):
                (r / d).mkdir(parents=True)
            (r / "_parts/demo.md").write_text('---\ntitle: Demo\norder: 1\npart_name: "demo"\n---\n')
            (r / "_docs/01-demo.md").write_text(good_doc)
            (r / "assets/diagrams/01-demo.svg").write_text('<svg xmlns="http://www.w3.org/2000/svg"/>')
            (r / "assets/diagrams/01-demo.excalidraw").write_text("{}")
            (r / "assets/diagrams/specs/01-demo.py").write_text("")
            return r

        base = run(build("base"))
        results.append(("baseline tree passes", not base.errors, base.errors))

        r = build("dash")
        (r / "_docs/01-demo.md").write_text(good_doc + "\nA dash — here.\n")
        e = run(r).errors
        results.append(("em dash caught", any("em dash" in x for x in e), e))

        r = build("orphan")
        (r / "assets/diagrams/specs/lonely.py").write_text("")
        e = run(r).errors
        results.append(("orphan spec caught", any("orphan spec" in x for x in e), e))

        r = build("selfref")
        (r / "_docs/01-demo.md").write_text(good_doc + "\nIn this chapter we cover it.\n")
        e = run(r).errors
        results.append(("self-reference caught", any("self-reference" in x for x in e), e))

        r = build("forward")
        (r / "_docs/01-demo.md").write_text(good_doc + "\nRun kubectl port-forward svc/x 8080.\n")
        e = run(r).errors
        results.append(("port-forward caught", any("forwarding" in x for x in e), e))

        r = build("xref")
        xref_doc = good_doc.replace(
            seg_anti, '## Anti-patterns\n\nDo not do the bad thing. An MCP tool call '
                      '(chapter 12) stops at the gateway.\n\n'
        ).replace(seg_refs, '## References\n\n- Chapter 12, token budgets.\n')
        (r / "_docs/01-demo.md").write_text(xref_doc)
        e = run(r).errors
        results.append(("cross-reference in prose caught, allowed under References",
                        sum("cross-reference" in x for x in e) == 1, e))
        (r / "_docs/01-demo.md").write_text(good_doc + "\nGeewax's chapter 14 calls it standard methods.\n")
        e = run(r).errors
        results.append(("another book's chapter cited as a source allowed", not any("cross-reference" in x for x in e), e))

        r = build("callout")
        (r / "_docs/01-demo.md").write_text(good_doc + "\n" + good_callout)
        e = run(r).errors
        results.append(("second callout caught", any("exactly one" in x for x in e), e))

        r = build("callout-shape")
        bad_callout = ('> **\U0001F916 Explore with your agent**\n> "Open the demo and check the rule."\n> Check: it holds.\n')
        (r / "_docs/01-demo.md").write_text(
            '---\ntitle: "Demo"\norder: 1\npart: demo\n---\n\nIntro text.\n\n'
            '{% include excalidraw.html file="01-demo" alt="Demo" %}\n\n## Pattern: Demo\n\n' + bad_callout)
        e = run(r).errors
        results.append(("old-shape callout (missing Intent/Contract/Build it here/elsewhere) caught",
                        any("is missing the" in x for x in e), e))

        r = build("bro")
        long_bro = BRO_HEAD + "\n\n```bash\n./verify.sh\n```\n\n" + " ".join(["word"] * 251) + "\n\n"
        (r / "_docs/01-demo.md").write_text(good_doc.replace(seg_bro, long_bro))
        rr = run(r)
        results.append(("251-word build section warned, not failed",
                        any("soft cap 250" in x for x in rr.warnings) and not any("Build, run" in x for x in rr.errors),
                        rr.errors + rr.warnings))

        r = build("bro-check")
        bad_bro = BRO_HEAD + "\n\nOnly check 12 fails.\n\n"
        (r / "_docs/01-demo.md").write_text(good_doc.replace(seg_bro, bad_bro))
        e = run(r).errors
        results.append(("check number in build section caught", any("check number" in x for x in e), e))

        r = build("spine-missing-lo")
        (r / "_docs/01-demo.md").write_text(
            seg_front + seg_wtm + seg_pattern + seg_bro + seg_anti + seg_wntu + seg_refs)
        e = run(r).errors
        results.append(("chapter spine: missing Learning objectives caught",
                        any("missing required heading '## Learning objectives'" in x for x in e), e))

        r = build("spine-missing-anti")
        (r / "_docs/01-demo.md").write_text(
            seg_front + seg_lo + seg_wtm + seg_pattern + seg_bro + seg_wntu + seg_refs)
        e = run(r).errors
        results.append(("chapter spine: missing Anti-patterns caught",
                        any("missing required heading '## Anti-patterns'" in x for x in e), e))

        r = build("spine-order")
        (r / "_docs/01-demo.md").write_text(
            seg_front + seg_lo + seg_wtm + seg_pattern + seg_anti + seg_bro + seg_wntu + seg_refs)
        e = run(r).errors
        results.append(("chapter spine: Anti-patterns before Build, run, observe caught",
                        any("'## Anti-patterns' must come after" in x for x in e), e))

        r = build("spine-missing-wntu")
        (r / "_docs/01-demo.md").write_text(
            seg_front + seg_lo + seg_wtm + seg_pattern + seg_bro + seg_anti + seg_refs)
        e = run(r).errors
        results.append(("chapter spine: pattern chapter missing When not to use it caught",
                        any("missing required heading '## When not to use it'" in x for x in e), e))

        r = build("spine-appendix-exempt")
        (r / "_docs/aa-appendix-demo.md").write_text(
            '---\ntitle: "Appendix"\norder: 99\npart: demo\n---\n\nJust prose, no spine at all.\n')
        e = run(r).errors
        results.append(("chapter spine: appendix (a*-*.md) exempt from the spine",
                        not any("chapter spine" in x for x in e), e))

        r = build("examples")
        (r / "examples/01-demo").mkdir(parents=True)
        (r / "examples/01-demo/verify.sh").write_text("#!/bin/bash\necho hi\n")
        e = run(r).errors
        results.append(("verify.sh missing fresh-state pattern caught",
                        any("must start fresh" in x for x in e), e))
        results.append(("verify.sh non-executable caught", any("not executable" in x for x in e), e))
        results.append(("missing README.md caught", any("missing README.md" in x for x in e), e))

        r = build("image")
        (r / "examples/01-demo").mkdir(parents=True)
        (r / "examples/01-demo/verify.sh").write_text(
            "#!/bin/bash\ndocker compose down -v --remove-orphans\n"
            "docker compose up -d --build --force-recreate --renew-anon-volumes --wait\n")
        (r / "examples/01-demo/verify.sh").chmod(0o755)
        (r / "examples/01-demo/README.md").write_text("# demo\n")
        (r / "examples/01-demo/compose.yaml").write_text("services:\n  x:\n    image: redis:latest\n")
        e = run(r).errors
        results.append((":latest image caught", any("uses :latest" in x for x in e), e))

    failures = [name for name, ok, _ in results if not ok]
    for name, ok, detail in results:
        print(("PASS " if ok else "FAIL ") + name)
        if not ok:
            for d in detail:
                print(f"    {d}")
    print(f"validate self-test: {'FAILED' if failures else 'passed'} ({len(failures)} failure(s))")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    rep = run(REPO)
    for w in rep.warnings:
        print(f"WARN {w}")
    for e in rep.errors:
        print(f"FAIL {e}")
    if rep.errors:
        print(f"validate: {len(rep.errors)} error(s), {len(rep.warnings)} warning(s)")
        return 1
    print(f"validate: passed ({len(rep.warnings)} warning(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main())
