---
layout: plan
title: "r03 plan"
render_with_liquid: false
---

# r03 plan

The approved scope for r03, recorded for the record once the iteration
closes. Supersedes nothing in `_plans/iteration-plan.md`; it only pins the
decisions specific to this release.

## Scope

- Chapters 03 to 06 (Build: synchronous APIs): Intent Endpoint (over CRUD),
  Search over Pagination, Batch Operation and Count Flag, Idempotency and
  Safe Retries.
- One runnable example per chapter: `examples/03-intent-endpoint`,
  `examples/04-search-over-pagination`, `examples/05-batch-and-count`,
  `examples/06-idempotency-safe-retries`.
- One figure per chapter, built from a spec: `03-intent-endpoint`,
  `04-search-over-pagination`, `05-batch-and-count`, `06-idempotency`.
- The full chapter table of contents published on the Chapters page
  (`_data/catalog.yml` plus the rewritten `chapters.html`), so every
  planned chapter and appendix is visible, not only the ones already
  written. Named `catalog.yml`, not `roadmap.yml`, to clear the voice
  scanner's "plan link in site chrome" ban-tier pattern (`\broadmap\b`),
  which exists to stop a reader-facing page from exposing internal
  planning vocabulary; the content and structure match what was asked for.
- Appendix F added to the outline: deterministic decisioning behind an
  agent tool.
- `_plans/claims.md` and `_plans/verification-log.md` evidence for all four
  chapters.

## Locked decisions

- Chapters 03 and 06 model the agent-facing write with Quarkus REST, never
  Camel `platform-http`; Camel stays on the event/EIP edge (the saga route
  and the Idempotent Consumer EIP), consistent with the house rule that
  Camel never terminates an HTTP request directly from r03 onward.
- Chapters 04 and 05 need no Camel at all: pure Quarkus REST and JDBC over
  the same `orders.orders` table, since neither pattern has an event or an
  EIP concern, only a response-shape one.
- Every measured contrast in chapter prose (call counts, byte totals) is the
  exact number recorded in `_plans/verification-log.md`'s 2026-11-18 run,
  never a rounded or estimated figure.
- The count flag's GraphQL equivalent (field selection returning only a
  `count` field) is named in chapter 05's prose only, not built as a second
  stack in this catalog; the cross-stack callout stays REST-first and
  states the contract, not a second implementation.
- Appendix F is distinct from Part 3's async determinism patterns: it
  answers the Prompt-as-Business-Logic anti-pattern with a rules or DMN
  engine behind a tool, not a workflow-step guarantee. It is scoped for a
  later iteration (grouped with Appendices A, C, D, E in the release
  table); no appendix chapter or example ships in r03.
- No stub `_docs` pages are created for any unbuilt chapter; the roadmap
  links only chapters 00 to 06 and shows every later chapter and appendix
  as a plain, unlinked row.

## Not in scope

- Chapters 07 to 12 (the rest of Part 1): deferred to r04 per the release
  table.
- Any change to `scripts/validate.py` or `CLAUDE.md`.
- A remote push or a merge request: r03 stays on `release/r03` for local
  review before any of that happens.
