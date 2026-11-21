---
layout: plan
title: "r17 plan (the companion decks: 3 thematic conference decks)"
render_with_liquid: false
output: false
---

# r17 Implementation Plan: the companion decks (the final deliverable)

Status: SCOPE GATED, plan drafted (owner 2026-11-21). Decks-last: this is the final
deliverable; chapters 00-38 + appendices A-G are content-complete and live-verified
(r14b/r15/r16).

**LOCKED DECISIONS (owner 2026-11-21):**
1. **3 thematic decks** (matches the WebSockets sibling in this series; each in its own
   `presentation/<name>/` dir with a `deck.js` builder and a versioned `.pptx`).
2. **Conference pacing, Red Hat house brand.** One idea per slide; diagram or short code
   callout, not dense code; thick speaker notes as the spoken script. Visual brand is the
   **`lgtm-presentation`** house style (Red Hat Display/Text/Mono, red #EE0000, logo on
   every slide), matching the WebSockets and REST sibling decks in this series. NOT the
   vendor-neutral `lgtm-presentation-conference` variant (owner decision 2026-11-21).

## The three decks (clean, non-overlapping part boundaries)

| Deck | Dir | Covers | Source chapters | Target slides |
|------|-----|--------|-----------------|---------------|
| 1. APIs for Agents & LLMs: the 101 | `presentation/apis-for-agents-101/` | The thesis, Foundations, MCP-as-facade concept | 00, 01, 02 + the conceptual headline of 12/13/15; teaser of 38 | ~40-45 |
| 2. Building Agent-Facing APIs | `presentation/building-agent-apis/` | The pattern catalog tour: Build sync + MCP + async | Parts 1-3 = ch 03-21 | ~50-55 |
| 3. Running Agent APIs in Production | `presentation/running-agent-apis-prod/` | Consume, Test, Ship, Run + the data-mesh final project | Parts 4-7 = ch 22-38; appendices referenced by concept | ~50 |

Boundary rule: Deck 1 sells the **concepts** (why agent APIs differ, the catalog's
organizing idea, MCP as a unified facade over REST/GraphQL/gRPC, the stack). Deck 2 is
the **build patterns** (Parts 1-3, one pattern per slide). Deck 3 is **consume ->
production** (Parts 4-7) ending on the final project. No pattern is covered twice.

### Deck 1 slide inventory (APIs for Agents & LLMs: the 101)
- Cover (custom panel; revision marker r17.0).
- Divider: Why agent-facing APIs are different.
  - Agents are not browsers: how an LLM consumes an API differs from a human/UI (ch01).
  - The cost model: tokens, round-trips, ambiguity, hallucinated calls (ch01).
- Divider: The organizing idea.
  - A pattern catalog by API lifecycle: Build / Consume / Test / Ship / Run (ch02).
  - Each chapter = one named pattern with a negative witness (ch02).
- Divider: MCP as the unified facade.
  - One facade over REST, GraphQL, gRPC (concept from 13/15).
  - MCP-first, single CDI bean; reshape not wrap (concept from 12/15).
  - Progressive discovery and domain-split toolsets keep the surface legible (14).
- Divider: The stack.
  - Quarkus + Camel + Kafka; Keycloak as the single book-wide IdP (ch00).
  - Pins that matter: MCP revision, quarkus-mcp-server, langchain4j, Ollama model (ch00).
- Divider: Audit and observability, both directions.
  - APIs consumed by agents, AND our own langchain4j code calling other APIs, on LGTM.
- Divider: Where this goes.
  - Teaser: the focused multi-domain data mesh (ch38) as the payoff.
- Close: how to read the catalog + references (ch02; credit EIP Hohpe/Woolf, Dehghani,
  the RFCs, Geewax where used).

### Deck 2 slide inventory (Building Agent-Facing APIs) - one pattern per slide
- Cover.
- Divider: Build - synchronous APIs (Part 1).
  - Intent Endpoint over CRUD (03) * Search over Pagination (04) * Batch Operation +
    Count Flag (05) * Idempotency & Safe Retries (06) * Strict Self-Describing Contract,
    OpenAPI 3.1 (07) * Semantic Metadata + llms.txt (08) * Token-Efficient Response /
    Agent BFF (09) * Instructive Error, RFC 9457 (10) * Propose/Preview/Commit (11) *
    MCP-First Single CDI Bean (12). Headline patterns (Intent Endpoint, Idempotency,
    Propose/Preview/Commit) may take 2 slides; the rest 1 each.
- Divider: Build - MCP packaging (Part 2).
  - Package an API as MCP Tools (13) * Domain-Split Toolset + Progressive Discovery (14)
    * Reshape not Wrap: heterogeneous protocols (15) * Securing & Gating MCP Tools (16).
- Divider: Build - asynchronous APIs (Part 3).
  - Event-Triggered Agent Wakeup (17) * Multi-Agent Choreography (18) * Human-in-the-Loop
    Gate (19) * Long-Running Agent Workflow (20) * Replayable Agent Memory / Event-Sourced
    Audit (21).
- Close: the pattern map (point to appendix AD) + references.
- Each pattern slide: pattern name as title; the problem it solves; the negative witness
  (what breaks without it); one diagram or a <=10-line code callout. Cross-reference by
  concept, never by chapter number.

### Deck 3 slide inventory (Running Agent APIs in Production)
- Cover.
- Divider: Consume (Part 4).
  - Consuming an MCP server from Claude Code (22) * Building a Claude Code skill (23) *
    LangChain4j as a programmatic consumer (24) * Consuming the async side (25) * The
    prompt-plus-contract loop and decision-rule review (26).
- Divider: Test and automate (Part 5).
  - Contract and manifest conformance (27) * Agent-behaviour eval with deterministic
    replay (28) * Scripted non-interactive consumption (29) * Testing async flows and the
    HITL gate (30).
- Divider: Ship (Part 6).
  - Publish the tool surface (31) * CI for agent-facing APIs (32).
- Divider: Run in production (Part 7).
  - Observability for agent traffic (33) * Auditing AI-using code (34) * Identity &
    authorization guardrails (35) * Traffic & resource guardrails (36) * Running on
    Kubernetes with Strimzi (37).
- Divider: Final project - the focused multi-domain data mesh (38).
  - Dehghani's 4 principles -> concrete artifacts * the governance plane (live Apicurio
    BACKWARD gate + Keycloak scopes + audit obligation) * exercise-it-yourself payoff.
- Close: appendices pointer (aa-ag, by concept) + references (credit Dehghani data mesh,
  RFC 9457, OpenTelemetry, Strimzi, Keycloak, Apicurio).

## Diagrams: reuse first, generate only gaps

The book already ships **47 SVG figures** in `assets/diagrams/` (one per chapter 00-38 and
per appendix aa-ag), a near 1:1 match to the deck slides. Executors **reuse these**: convert
each needed SVG to PNG with `lgtm-diagram-generator/scripts/svg_to_png.py` (1400px; keeps the
EIP arrow glyphs that bare cairosvg drops) into the deck's `png/`. Only deck-only concepts
with no book figure (the three covers, a possible Deck 1 lifecycle-arc overview) get a new
`diagrams.py` Scene via `dgen.py`. This keeps the decks visually consistent with the book and
makes Wave A mostly conversion, not authoring. Map of reuse (chapter number -> `NN-*.svg`):
Deck 1 reuses 00, 01, 02-pattern-form, 02-taxonomy, 13-mcp-triad, 15-reshape-grpc, 38 (teaser);
Deck 2 reuses 03-21 (all present); Deck 3 reuses 22-38 (all present). Log reused vs new.

## Waves (lgtm-relay)

- **G0 [DONE]:** owner gated deck count (3 thematic) and audience (conference).
- **Wave 0 (orchestrator, setup):** in each `presentation/<name>/` dir, copy the
  `lgtm-presentation` bundled files (`deck-helpers.js`, `deck.template.js -> deck.js`,
  `assets/`, and if diagrams are needed `dgen.py`/`build_diagrams.py`/
  `diagrams.template.py`); `npm install pptxgenjs` locally (it is not global; the sibling
  installs per-dir); `pip install --user cairosvg` for SVG->PNG (missing on this host).
  Commit the scaffold.
- **Wave A (orchestrator):** build the shared diagram inventory - list existing book
  figures to reuse per deck, and the gap list to generate. Author gap Scenes; run
  `build_diagrams.py`; land PNGs in each deck's `png/`.
- **Wave B (3 parallel Sonnet executors, one per deck - independent dirs):** write each
  `deck.js` slide-by-slide against the inventory above; add thick speaker notes on every
  slide; `node deck.js` -> `<Name>-r17.0.pptx`. Each executor prompt restates ALL house
  conventions (subagents inherit no skill) and the deck's own inventory + source chapter
  paths. Decks are in separate dirs -> safe to run concurrently.
- **Wave C (Opus validate):** per-deck brand/consistency/voice pass -
  `soffice --convert-to pdf` + `pdftoppm` eyeball of every slide; `notesCount == slide
  count`; logo on every slide; cross-ref by concept not number; `lgtm-professional-voice`
  scan on all speaker notes (0 ban-tier, no em/en dashes, no leaked chapter codes); every
  source credited on a references slide; scrub pptx author/title metadata of the
  hardcoded owner name (public repo). Cap repair rounds at 2.
- **Ship:** `host_ids.py scrub` + `validate.py`; squash-merge PR; tag `r17.0`; notes-only
  GitHub release. Decks are `<Name>-r17.0.pptx` per the versioning rule.

## Acceptance criteria
- Three `.pptx` files exist and open in LibreOffice: `apis-for-agents-101`,
  `building-agent-apis`, `running-agent-apis-prod`, each `<Name>-r17.0.pptx`.
- Every slide carries the Red Hat logo (footer or divider/cover) and non-empty speaker
  notes; `notesCount == slide count` for each deck.
- Deck 1 covers the thesis/foundations/MCP-facade; Deck 2 covers every Build pattern
  03-21 (one slide minimum each, no omissions); Deck 3 covers Parts 4-7 and ends on the
  final project. No pattern appears in two decks.
- `lgtm-professional-voice` scan: 0 ban-tier across all speaker notes and slide text; no
  em/en dashes; no leaked chapter/part numbers or internal codes in slide-visible text
  (cross-ref by concept).
- Every borrowed idea is credited (EIP Hohpe/Woolf, Dehghani data mesh, the cited RFCs,
  Geewax register, OpenTelemetry/Strimzi/Keycloak/Apicurio); each deck has a references
  slide; nothing copied verbatim.
- pptx author/title metadata scrubbed of the hardcoded owner name (public repo).
- `validate.py` and `host_ids.py scrub` clean; site still builds.

## Risks (+ detection)
- **`newDeck()` hardcodes owner "Robert Sedor" in pptx metadata.** DETECT: inspect pptx
  core.xml / docProps after build. MITIGATE: scrub in Wave C (known gotcha from the
  quantum deck). Blocking for a public repo.
- **Regenerating diagrams the book already has** -> visual drift from the chapters.
  DETECT: Wave A inventory. MITIGATE: reuse-first; generate only gaps.
- **Speaker notes drift out of voice** (decks tempt marketing register). DETECT:
  `lgtm-professional-voice` scan in Wave C. MITIGATE: restate rule 9 (no editorial
  padding) + the Geewax register in executor prompts.
- **Leaked chapter numbers in slide text** (the catalog is number-heavy). DETECT: grep in
  Wave C. MITIGATE: cross-ref by concept; numbers allowed only in the plan/notes, not on
  slides.
- **Toolchain gaps** (pptxgenjs not global, cairosvg missing). DETECT: Wave 0 setup.
  MITIGATE: per-dir `npm install`; `pip install --user cairosvg` (or reuse book PNGs and
  skip SVG->PNG entirely).
- **A deck too dense for a conference** (one-idea-per-slide violated). DETECT: Wave C
  eyeball. MITIGATE: split dense slides; push detail into notes.

## Out of scope
- No new chapters, examples, or pins; decks mine finished content only.
- The whole-book workshop remains a separate later exploration
  ([[project_api_ch38_handson_workshop]] item 2), not part of r17.
- No companion `.docx`; decks only (can be a follow-up if the owner wants one).
