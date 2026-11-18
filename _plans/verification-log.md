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
