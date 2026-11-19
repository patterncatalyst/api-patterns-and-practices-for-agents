---
layout: plan
title: "r06 plan"
render_with_liquid: false
---

# r06 plan

The approved scope for r06, recorded for the record once the iteration
closes. Supersedes nothing in `_plans/iteration-plan.md`; it only pins the
decisions specific to this release.

## Scope

- Retrofit every non-appendix chapter (00 to 12) to the EIP-Camel chapter
  spine: `## Learning objectives` (first heading, bulleted) and
  `## Why this matters` (second heading) open the chapter; `## Anti-patterns`
  (distinct from, and placed after, `## Build, run, observe`) precedes
  `## References` on every chapter; pattern chapters keep
  `## When not to use it` between `## Anti-patterns` and `## References`.
  Chapters 13 to 16 already carried the full spine from r05 and needed no
  prose change.
- Option (a) was chosen over a partial retrofit: all twelve chapters (00 to
  12) in this one release, not a split across two iterations, since the
  spine is a structural rule, not new content, and a half-retrofitted tree
  would leave `scripts/validate.py` unable to gate the whole book at once.
- Add `check_chapter_spine` to `scripts/validate.py` so the spine is a
  deterministic gate, not an advisory convention: it enforces the required
  heading set, the Learning-objectives-first/Why-this-matters-second
  position, the bulleted Learning objectives, the Anti-patterns-after-
  Build-run-observe order, and the When-not-to-use-it requirement on
  pattern chapters. Appendices (`a*-*.md`) are exempt; they are unbuilt and
  decided later. Extends the `--self-test` harness's `good_doc` fixture and
  adds defect cases for the new rule.
- Document the spine in `CLAUDE.md`'s chapter writing rules.
- Renumber `_plans/iteration-plan.md`'s release table: the async chapters
  (17 to 21) move from r06 to r07, and every later release shifts down by
  one (r07 to r08, ..., r15 to r16 for the decks-last release). Chapter
  numbers (17 to 38, A to F) are unchanged; only the `rNN` release labels
  move.

## Locked decisions

- This is a prose- and tooling-only retrofit: no `_docs` content changes
  beyond the heading restructure already landed in Waves A and B, no
  `examples` or `contracts` changes, no new runtime evidence. `_plans/claims.md`
  and `_plans/verification-log.md` get a record-keeping entry, not a new
  claim.
- `scripts/validate.py --self-test` and a full `python3 scripts/validate.py`
  run must both pass green before this release closes, confirming 00 to 16
  all satisfy `check_chapter_spine`.

## Not in scope

- Any `_docs`, `examples` or `contracts` change: this release only touches
  `scripts/validate.py`, `CLAUDE.md` and `_plans/*`.
- Chapters 17 to 21 (Build/async): now r07 per the renumbered release table.
- A remote push or a merge request: r06 stays on `release/r06` for local
  review before any of that happens.
