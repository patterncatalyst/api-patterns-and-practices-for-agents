---
layout: plan
title: "r01 plan"
render_with_liquid: false
---

# r01 plan: skeleton and plan only

Approved scope for r01, the local-only skeleton build of "API Patterns &
Practices: APIs for Agents & LLMs" (`api-patterns-and-practices-for-agents`),
an EIP-Camel-style pattern catalog for building APIs that AI agents and LLMs
consume, on Quarkus, Apache Camel and Kafka. No Python/FastAPI tab; the
stack-neutral contract in each chapter's cross-stack callout is how patterns
transfer to another stack.

## Scope gate

r01 is **skeleton and plan only**. No chapter prose (`_docs/NN-*.md`) and no
runnable example projects (`examples/NN-*/`) ship in this release; creating
either is a scope violation for this iteration. The empty structure, the
tooling, and the plan artifacts are the deliverable.

## What r01 built

- Root hygiene: `.gitignore`, `.gitattributes` (with the `examples/**`
  linguist re-inclusion), dual licensing (`LICENSE` Apache-2.0,
  `LICENSE-docs.md` CC BY 4.0), `README.md`, `CLAUDE.md`.
- The Jekyll site: `_config.yml`, `Gemfile`/`Gemfile.lock`, `_layouts/`,
  `_includes/` (header nav: Home, Chapters, Examples, Diagrams,
  Bibliography, GitHub), `assets/css/site.css`, `assets/diagrams/`,
  `index.html`, `chapters.html`, `examples.html`, `diagrams.html`,
  `bibliography.md`, and nine `_parts/*.md` cards (Parts 0 to 8), all
  rendering a clean zero-chapter "Coming soon" state.
- `_docs/` and `_example_pages/` are empty (`.gitkeep` only).
- Tooling and CI: `scripts/validate.py` (adapted for this book's cross-stack
  callout shape and the stand-alone-chapter rule), `scripts/host_ids.py`,
  the vendored voice and idiomatic scanners, `scripts/site-build.sh`,
  `scripts/generate_diagram.py`, `scripts/build_diagrams_page.py`,
  `scripts/build-diagrams.sh`, `scripts/verify-all.sh` (a clean no-op stub
  until examples exist), `.github/workflows/pages.yml`,
  `.github/workflows/ci.yml` (its Maven job is a no-op until a `pom.xml`
  exists).
- `contracts/` with a README describing what lands there and when the drift
  gate arrives.
- `examples/_infra/` declaring the shared compose stack (Kafka KRaft,
  PostgreSQL on the CloudNativePG operand image, Apicurio, Keycloak,
  Ollama, with Grafana LGTM in a separate compose file), pinned and
  health-checked but not yet referenced by any example.
  `examples/_k8s/` is empty. `examples/domain-model/` is a directory and a
  README; the buildable module lands in r02.
- `_plans/versions.md`, `_plans/iteration-plan.md` (the full Part-to-chapter
  outline and the r01 to r15 release table), `_plans/domain-model.md`,
  `_plans/claims.md` and `_plans/verification-log.md` (empty scaffolds),
  and this file.

## What r01 does not build

Chapter prose, runnable examples, the wired compose stack, the
`examples/domain-model` Maven module, and the deck. Those land starting r02.
