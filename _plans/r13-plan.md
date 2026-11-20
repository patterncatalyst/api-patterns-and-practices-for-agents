---
layout: plan
title: "r13 plan (appendices A, C, D, E, F, G)"
render_with_liquid: false
output: false
---

# r13 Implementation Plan: the appendices (A, C, D, E, F) + G (structured outputs)

Status: DRAFT, pending owner approval. Part 8 appendices, all except B (OpenShift Local, deferred to r14). All house gates apply. Appendices are spine-exempt (`validate.py check_chapter_spine` skips `a*-*.md`) and already xref-exempt (the xref check skips `a*-*.md`), but every other house rule holds: front matter, no em/en dashes, no host ids, voice, idiomatic, evidence in `_plans/claims.md`, sources credited in `bibliography.md`.

## Scope

Six appendices (B is r14):

| Letter | File | Title | Type | Anchor / source | Runnable? | New deps? |
|---|---|---|---|---|---|---|
| A | `_docs/aa-llms-txt-in-depth.md` | llms.txt in depth | reference / synthesis | ch08, ch31; `contracts/08-llms.txt`, `contracts/31-llms.txt` | no | none |
| C | `_docs/ac-streaming-comparison.md` | Streaming comparison (WebSockets, webhooks, gRPC) + gRPC-behind-facade note | reference / comparison | ch15, ch17, ch25; MCP Streamable HTTP | no (reference table + snippets) | none |
| D | `_docs/ad-pattern-map-and-glossary.md` | Pattern map, glossary, and cross-stack transfer table | pure reference | the whole catalog (00 to 38); the one callout per pattern chapter | no | none |
| E | `_docs/ae-consuming-skill-end-to-end.md` | Building the consuming skill in Claude Code, end to end | walkthrough | the existing `skills/apa-order-ops/`; ch22, ch23 | the skill artifact (already present); a scripts check | none |
| F | `_docs/af-deterministic-decisioning.md` + `examples/af-deterministic-decisioning/` | Deterministic decisioning behind an agent tool (rules/DMN) | runnable example | reimplements ch26's refund-approval decision as a rules/DMN ruleset | yes (verify.sh + negative witness) | YES: a rules/DMN engine (survey at G0) |
| G | `_docs/ag-structured-outputs.md` + `examples/ag-structured-outputs/` | Structured outputs: typed, consistent agent I/O | synthesis + runnable demo | ch24, ch26, ch28, ch36 (type the API/tool/eval boundary); this appendix types the model-generation side | yes (untyped-vs-typed consistency demo + negative witness) | none new (uses quarkus-langchain4j structured output + Ollama `format`, already pinned); see [[project_api_typed_ai_appendix]] |

## RECOMMENDED SPLIT: r13a (reference) then r13b (runnable)

- **r13a = A, C, D, E** — reference, comparison, pattern map/glossary, and the consuming-skill walkthrough. No new dependencies, no new runnable example module (E documents the existing `skills/apa-order-ops`; a light scripts check at most). Content-heavy, low build risk. One clean "reference appendices" checkpoint.
- **r13b = F, G** — the two runnable example appendices. F adds a rules/DMN engine (new pin, compatibility risk against Quarkus 3.40.1.2) and a full example + verify.sh + negative witness. G adds a runnable structured-outputs demo (no new pin, verifiable without a cluster). One "runnable appendices" checkpoint.

Rationale: mirrors the r12a/r12b split. The reference appendices are independent of the engine survey and can ship first as a clean checkpoint; the runnable appendices (especially F's new engine) carry the real risk and deserve their own gate. If the owner prefers one iteration, the waves below still serialize cleanly.

## Per-appendix design

### A llms.txt in depth (`_docs/aa-llms-txt-in-depth.md`)
- Deep reference on the `/llms.txt` convention for agent discoverability: what it is, its structure, how a self-describing tool surface renders it from the live `ToolManager` (as ch31 does), how it differs from robots.txt/sitemap/OpenAPI, and what belongs in it for an agent caller. Synthesizes ch08 (semantic metadata) and ch31 (publishing the surface) without re-teaching them.
- No runnable module; references the committed `contracts/08-llms.txt` and `contracts/31-llms.txt`. Cites the llms.txt proposal in `bibliography.md`.

### C Streaming comparison (`_docs/ac-streaming-comparison.md`)
- Comparison appendix: when an agent-facing surface should use request/response (REST/MCP tool call), server push (SSE / MCP Streamable HTTP), bidirectional streaming (WebSockets, gRPC streaming), webhooks, or a durable event log (Kafka, the book's async spine). A decision table keyed on latency, durability, replay, fan-out, and agent-consumability, plus the "reshape, do not wrap" gRPC-behind-a-facade note from ch15.
- Reference + short illustrative snippets only (no new module). Honest about what the book builds (Kafka async, MCP Streamable HTTP) vs what it only compares.

### D Pattern map, glossary, and cross-stack transfer table (`_docs/ad-pattern-map-and-glossary.md`)
- The cross-reference (xref-exempt): a map of every pattern (00 to 38), its part, the problem it answers, and its relationships (which patterns compose, which are alternatives), in the spirit of EIP's pattern relationships. Plus a glossary of the book's terms, and a cross-stack transfer table aggregating the "Build it elsewhere" callouts (Python/FastAPI, Strawberry GraphQL) into one reference.
- Pure reference, no example. Highest content-aggregation effort; no code, no deps.

### E Building the consuming skill end to end (`_docs/ae-consuming-skill-end-to-end.md`)
- Walkthrough of building a Claude Code skill that consumes the MCP tool surface, anchored on the existing `skills/apa-order-ops/` (SKILL.md, references/, scripts/). Shows the anatomy (SKILL.md front matter, progressive-disclosure references, helper scripts), how it consumes the published tool surface, and how to verify it. Relates to ch22 (consuming from Claude Code) and ch23 (building a skill) without re-teaching them.
- Artifact already exists; the appendix may add a small scripts check (shellcheck + a dry-run of `scripts/order-status.sh` against a fixture) rather than a full example module.

### F Deterministic decisioning behind an agent tool (`_docs/af-deterministic-decisioning.md` + `examples/af-deterministic-decisioning/`)
- Reimplements the refund-approval decision (ch26's hand-rolled JSON-rule evaluator) as a rules/DMN engine behind an MCP tool: the concrete answer to the Prompt-as-Business-Logic anti-pattern and the Model-Reasons / Application-Controls principle. The model proposes; the engine decides deterministically; the tool returns the verdict with the failed clause.
- **New dependency (G0 survey required):** a rules/DMN engine. RECOMMENDED: Kogito on Quarkus with a DMN decision model (`kogito-quarkus-decisions`, the Quarkus-native evolution of Drools, business-readable DMN pairs with "commit the rule as a model, not code"). FALLBACK if Kogito does not support Quarkus 3.40.1.2 at G0: the Drools rules engine as a plain library (`kie-api` / `drools-*`, DRL rules) invoked from a CDI bean, which has no Quarkus-version coupling. Survey both upstream at G0, pin the latest stable that is compatible, record the choice and reason in `_plans/versions.md`.
- Runnable: `verify.sh` asserts the same observable effect as ch26 (durable verdict row, approve/reject outcomes, the failed clause on rejection) and a negative witness (weaken a rule clause, the previously-rejected proposal is now approved). `mvn -B verify` with a fixture lane so CI needs no model. Reuses the shipping-domain refund entities.

### G Structured outputs: typed, consistent agent I/O (`_docs/ag-structured-outputs.md` + `examples/ag-structured-outputs/`)
- Scope CONFIRMED (owner 2026-11-20): synthesis + runnable demo. Thesis: consistency (same intent to same output shape and semantic class), not determinism (byte-identical reproduction, which is not a reliable contract). Lever map strongest to weakest: type the output (Ollama `format` + JSON schema, grammar-constrained decoding), type the tool surface (MCP `outputSchema`/`structuredContent`), validate + repair at the boundary, constrain the input space, and why `seed`/`temperature` is variance reduction, not consistency. Synthesizes the boundaries the book already types (ch24 captured result not prose, ch26 deterministic review, ch28 deterministic eval, ch36 JSON-schema ingest) and adds the model-generation-side typing none of them cover.
- Runnable: an untyped-vs-typed consistency demo on Ollama + qwen3 (N runs of the same prompt: the untyped path drifts in shape, the typed schema-constrained path is shape-stable every run; values may still vary, which is the teaching point). Negative witness: drop the schema constraint and the downstream parse breaks. Verifiable live without a cluster (`ollama run`/`curl` + `quarkus:dev`).
- Research, buildability verdict, and the version gotchas are banked in memory ([[project_api_typed_ai_appendix]]): Ollama `format`+schema works on 0.40.16; quarkus-langchain4j typed returns auto-pass the schema; structured output is non-streaming and off-by-default; MCP auto-validator is 2.1.0.Beta1 (after the pinned 2.0.4, so validate structured content by hand if demoed); MCP 2025-11-25 needs object-root schemas; terminology "structured outputs", not "strongly typed AI".

## Tooling (Wave A, before any runnable appendix example)
- **Extend `scripts/validate.py` appendix coverage from `a[a-e]` to `a[a-g]`** (`EXAMPLE_DIR_RE` and the `examples/a[a-e]-*/**` globs) so Appendix F (`af-`) and G (`ag-`) example dirs are recognized by `check_examples`, the image-pin check, and the fresh-run contract. Add `--self-test` cases (an `af-`/`ag-` example dir is linted; an out-of-range letter is still ignored). Low risk, deterministic.

## Waves (lgtm-relay; serialize on shared files; checkpoint-commit each appendix)
Shared files forcing serialization: `_plans/versions.md`, `_plans/claims.md`, `_plans/verification-log.md`, `bibliography.md`, `scripts/validate.py`, `_parts/08-appendices.md`.

- **G0 survey + owner gate (serial):** survey the F engine (Kogito DMN vs Drools library) upstream for the latest stable compatible with Quarkus 3.40.1.2; record the candidate + reason in versions.md. Resolve the owner questions (split, F engine, filenames/ordering). Files: versions.md, r13-plan.md.
- **Wave A tooling (serial, Sonnet):** extend validate.py `a[a-e]` to `a[a-g]` + self-tests.
- **r13a build (Sonnet, one appendix each; serialize on shared files):**
  - Wave Ba: A llms.txt in depth (+ figure if it helps).
  - Wave Bc: C streaming comparison (+ decision-table figure).
  - Wave Bd: D pattern map, glossary, cross-stack transfer table (+ pattern-relationship figure).
  - Wave Be: E consuming-skill walkthrough (+ optional scripts check on `skills/apa-order-ops`).
- **r13a static gate + validate + ship (Opus):** full static gate; Opus validation (voice + claims audit + attribution); ship r13a.0 (if split).
- **r13b build (Sonnet):**
  - Wave Bf: F deterministic decisioning example + chapter + verify.sh + figure; versions.md pin.
  - Wave Bg: G structured outputs example + chapter + verify.sh + figure.
- **r13b verify (local, no cluster needed):** `mvn -B verify` (MODEL=off) + live `verify.sh` for F and G, logged in verification-log; claims Verified only on a real pass.
- **r13b static gate + validate + ship (Opus):** ship r13b.0.

## Acceptance criteria
- Each appendix has valid front matter (title, order, `part: "Appendices"`), passes validate.py (spine-exempt but all other checks), voice-scan 0 ban-tier, idiomatic-scan 0 ban-tier, host_ids clean, site-build clean. No em/en dashes. Sources credited in bibliography.
- A, C, D, E: content complete and accurate; D maps every pattern 00 to 38 and aggregates the cross-stack callouts; E's `skills/apa-order-ops` walkthrough matches the committed skill.
- F: `examples/af-deterministic-decisioning` builds (`mvn -B verify`, MODEL=off), its `verify.sh` asserts the observable verdict and the negative witness bites and recovers; the engine pin is recorded in versions.md with a reason; UBI10 image; no forwarding.
- G: `examples/ag-structured-outputs` demonstrates the untyped-vs-typed consistency contrast with a biting negative witness, verifiable without a cluster; claims mapped to evidence.
- Iteration: full static gate green (CI); runnable appendices verified locally and logged; no floating tags; owner-confirmed.

## Risks (+ detection)
- **F engine vs Quarkus 3.40.1.2 compatibility:** Kogito/Drools Quarkus extensions can lag the Quarkus LTS. DETECT at G0 survey + first `mvn -B verify`. MITIGATE: fall back to the Drools engine as a plain library (no Quarkus-version coupling) invoked from a bean.
- **F scope creep into a rules tutorial:** keep it to the one refund-approval decision, mirroring ch26's observable effect; the lesson is deterministic decisioning behind a tool, not the engine's full surface.
- **D staleness:** the pattern map must match the shipped chapters exactly. DETECT: cross-check every row against `_docs/*.md` titles and the one callout per chapter.
- **G structured-output version gotchas:** non-streaming, off-by-default, MCP object-root rule, the 1.20.2 guardrail bug. Already captured in [[project_api_typed_ai_appendix]]; honor them in the build.
- **Appendix letter/filename drift:** B is reserved for r14 (CRC, `ab-`); A=aa, C=ac, D=ad, E=ae, F=af, G=ag. Confirm at G0.

## Open questions for the owner
1. **Split r13a (A, C, D, E) then r13b (F, G), or one r13?** RECOMMEND SPLIT (reference checkpoint, then the runnable/new-engine checkpoint).
2. **Appendix F engine:** RECOMMEND Kogito DMN on Quarkus if compatible with 3.40.1.2 at G0, else Drools as a plain library. Owner preference between a DMN decision model and DRL rules?
3. **Appendix G placement:** confirm it ships as Appendix G (new letter) in r13, as scoped.
4. **Any new pin** (F's engine) needs owner confirmation + a recorded reason at G0 before Wave Bf.
