---
layout: plan
title: "r15 plan (retro: idiomatic + voice + QA sweep + fresh re-run)"
render_with_liquid: false
output: false
---

# r15 Implementation Plan: the retro (quality sweep before the decks)

Status: DRAFT, pending owner approval. No new content. A regression and quality pass across the whole book (chapters 00-38 + appendices A-G, 40 example `verify.sh` lanes, the minikube `examples/_k8s` and CRC `examples/_openshift` layers) before the final deliverable (r16 decks). All house gates apply.

## What is already covered (so r15 does not redo it)
- **CI runs `mvn verify` on every example on every PR** (green as of r14b). The per-example build/test lane is continuously verified; r15 does not need to re-run it manually.
- **The cluster examples were live-verified this session:** ch37 + ch38 on minikube (r12a/r12b) and Appendix B on CRC (r14b). Re-running those heavy cluster lanes again is redundant unless a shared file changed; r15 relies on that recent verification.
- The ban-tier scanners (`voice-scan.sh`, `idiomatic-scan.sh`) are green corpus-wide. r15's reviews go DEEPER than the scanners (register, reinvented-wheel, deprecated APIs), catching what a ban-tier regex cannot.

## The real regression gap r15 closes
- **Live `verify.sh` drift on the compose examples (00-36 + af, ag).** Each was run live in its own iteration (r03-r11, r13b), but shared files (`examples/domain-model`, `examples/_infra/compose.yaml`, `scripts/*`) have changed since. CI cannot run the live compose lane. A fresh live re-run is the only thing that catches runtime drift.
- **Deep idiomatic + voice review** across the full corpus, applying `lgtm-idiomatic-review` (code) and `lgtm-professional-voice` (prose) rather than only the scanners.
- **Claims audit:** every factual claim in prose maps to an evidence row in `_plans/claims.md`, and every claims row still holds.

## RECOMMENDED SPLIT: r15a (static, fast) then r15b (live re-run, heavy)
- **r15a** (no cluster, no long runs): the deep `lgtm-idiomatic-review` pass across all examples; the deep `lgtm-professional-voice` pass across all docs, READMEs and example pages; a full claims audit (claim to evidence); a cross-reference / dead-link / front-matter / bibliography-completeness check; `host_ids.py scrub`; `validate.py`. Fix findings in place. Ship r15a.0.
- **r15b** (the heavy lane, owner-gated): a full fresh live re-run of the compose examples' `verify.sh` (each `down -v` to `up --build --wait` to `down -v`), one stack at a time, logged in `_plans/verification-log.md`. Roughly 3 to 6 hours depending on how many touch Ollama inference. Ship r15b.0.

## r15b live re-run scope (owner decision)
- **Full:** all ~38 compose examples (00-36 + af + ag). Maximum regression safety; hours of sequential compose cycles, several with cold model inference.
- **Targeted:** only the examples whose dependencies changed since their last live verification (anything depending on `examples/domain-model` or `examples/_infra` if those moved) plus a representative sample per Part, relying on CI's `mvn verify` for the rest. Faster; leans on the build lane for the unchanged majority.
- **Skip the clusters:** ch37/ch38 (minikube) and Appendix B (CRC) are NOT re-run (verified this session); re-run only if r15a's review changes a file they depend on.

## Waves (lgtm-relay)
- **G0:** resolve owner questions (split; r15b scope; review depth).
- **r15a Wave A (parallel Opus/Sonnet reviewers):** `lgtm-idiomatic-review` over the example code (by Part, fan out); `lgtm-professional-voice` over the prose (by Part). Each returns findings ranked; the orchestrator applies substantive fixes (behavior-preserving for code; register for prose), re-scans.
- **r15a Wave B:** claims audit + xref/link/front-matter/bibliography check; host scrub; full static gate; ship r15a.0.
- **r15b Wave C (orchestrator, local, one stack at a time):** fresh live `verify.sh` per the chosen scope; log each; fix any drift (feed the specific failure to a Sonnet executor, re-verify); ship r15b.0.

## Acceptance criteria
- r15a: `lgtm-idiomatic-review` and `lgtm-professional-voice` applied corpus-wide, substantive findings fixed, both scanners still 0 ban-tier; claims audit complete (no claim without evidence; no stale claim); validate/host_ids clean; site builds. No behavior change to any example from the idiomatic pass (re-verified by CI `mvn verify`).
- r15b: every in-scope example's live `verify.sh` passes fresh, logged with its pass count and date; any drift found is fixed and re-verified; one stack at a time, stopped when idle.
- No new pins; no new content; "final project" never "capstone"; no em/en dashes; no host-id leaks.

## Risks (+ detection)
- **r15b is long and machine-tying.** DETECT: wall-clock. MITIGATE: owner-gated; batched; one stack at a time; the orchestrator drives and logs.
- **An idiomatic "fix" changes behavior.** DETECT: CI `mvn verify` after the pass; any example whose test breaks is reverted or re-fixed.
- **A deep-review fix introduces a voice/validate regression.** DETECT: re-run the full static gate after each batch of fixes.
- **Drift found in an old compose example** (shared-file change broke it). DETECT: the live re-run; MITIGATE: fix + re-verify in r15b.

## Open questions for the owner
1. **Split r15a (static) then r15b (live re-run)?** RECOMMEND SPLIT.
2. **r15b live re-run scope:** full (~38 compose examples, hours) or targeted (changed-deps + a per-Part sample, leaning on CI's `mvn verify`)?
3. **Idiomatic + voice review depth:** full corpus deep pass (RECOMMEND, it is the point of the retro) or a lighter spot-check?
