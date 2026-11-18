---
layout: plan
title: "Verification log"
render_with_liquid: false
---

# Verification log

Code stays `unverified` until it is actually run in the real target
environment. "Verified" means the example produced its claimed observable
effect, not that it ran without error. This log records every live run of
every example's `verify.sh`, replacing the per-chapter verification footer
the house style omits from reader-facing pages.

r01 ships no examples, so there are no runs yet. Entries start with r02, one
row per live run:

| Date | Example | Result | Notes |
|---|---|---|---|
| 2026-11-18 | 00-stack-check | 18/18 PASS | `./verify.sh` live run on a fresh `down -v` / `up --wait` cycle. Asserted: all 7 containers healthy; a valid `Order` accepted (202) and its record on `apa.orders.placed` keyed by id; the same order's row in the Flyway-migrated `orders.stack_check` table; Apicurio and the Keycloak `apa` realm's OIDC discovery reachable; a `service.name=stack-check` trace in Tempo, metric in Prometheus and log line in Loki; the assistant's `orderStatus` tool-call round trip (asserted via the tool's own log line, not model prose, since qwen3's tool calling is nondeterministic); and the negative witness (invalid `Order`, quantity 0, gets 400 with the topic offset unchanged). Negative witness confirmed to bite: with the `onException` guard in `OrderIntakeRoute` removed, `mvn -B verify` failed 2 tests and the live run failed at check 17 (17/18), then the guard was restored and both reverified green. |
| 2026-11-18 | 01-why-agent-apis | `mvn -B verify` 7/7 PASS; `./verify.sh` (replay) 6/6 PASS; `LIVE=1 ./verify.sh` 10/10 PASS | `mvn -B verify` exercises `OrderOpsService` and both tool classes directly (no model, no network): the good contract's single call succeeds and names the problem on failure; the bad contract needs all three tools and returns a bare opaque code for a missing order, a mismatched refund amount, and an unrecognized status spelling. `./verify.sh` replays the committed fixtures (`fixtures/good-run.json`, `fixtures/bad-run.json`, recorded once against `qwen3:4b-instruct-2507-q4_K_M`, `temperature=0`, `seed=42`) and asserts: good contract completes in exactly 1 tool call with 0 argument errors and an actually-completed refund; bad contract needs more calls (5 > 1) and spends more tokens (3185 > 759); and the negative witness, that the good contract's own one-call pass rule fails when applied to the bad contract's recorded run (bad took 5 calls, not 1). `LIVE=1 ./verify.sh` brought up a fresh Ollama-plus-service stack, called `/run/good` and `/run/bad` against the real model, and reproduced the fixture's exact numbers (good: 1 call, 0 errors, 759 tokens, refunded; bad: 5 calls, 2 errors, 3185 tokens, not refunded), then tore the stack down with `down -v`. Mutation check: editing `fixtures/bad-run.json` to claim `toolCalls:1` makes checks 4 and 6 fail (4/6 PASS); editing `fixtures/good-run.json` to claim `refundCompleted:false` makes check 3 fail (5/6 PASS); editing `fixtures/bad-run.json` to claim `totalTokens:100` makes check 5 fail (5/6 PASS); fixtures restored byte-identical afterward and reverified at 6/6. |
