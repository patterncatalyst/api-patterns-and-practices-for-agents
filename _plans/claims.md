---
layout: plan
title: "Claims"
render_with_liquid: false
---

# Claims

Every factual claim made in a chapter (what a runtime or tool does, an error
message, a count, what a figure shows) is backed by a named test in the
chapter's example, a run quoted from real output, or a fetched primary
source. This file tracks that evidence per claim; evidence itself is never
written into the chapter prose.

r01 ships no chapters, so there are no claims yet. Entries start with r02, one
row per claim:

| Chapter | Claim | Evidence | Status |
|---|---|---|---|
| 00 (stack-check) | A valid `Order` posted to `/orders` gets 202 and is published to `apa.orders.placed`, keyed by order id | `OrderIntakeTest.validOrderIsAcceptedAndPublishedKeyedById`; `verify.sh` checks 8-9, 2026-11-18 run (18/18) | Verified |
| 00 (stack-check) | An invalid `Order` (quantity 0, non-positive amount, or malformed JSON) is rejected with 400 and never reaches the topic | `OrderIntakeTest.invalidOrderIsRejectedAndNothingIsPublished`, `blankCustomerIsRejectedWith400`; `verify.sh` checks 17-18 (negative witness) | Verified |
| 00 (stack-check) | Removing the route's `onException` guard breaks the 400 contract | `mvn -B verify` with the guard removed, 2026-11-18: 2 test failures (expected 400, got 500); live `verify.sh` failed at check 17 (17/18) with the same change; guard restored, both reverified green | Verified |
| 00 (stack-check) | Each accepted order is recorded in the `orders` PostgreSQL schema that Flyway migrated at startup | `OrderRecordStore`; `verify.sh` check 10 (direct `psql` read of `orders.stack_check`), 2026-11-18 run | Verified |
| 00 (stack-check) | A local langchain4j `AiService` backed directly by `quarkus-langchain4j-ollama` can call a registered `@Tool`, and the call is observable independent of the model's own wording | `OrderStatusTool`'s log line and Micrometer counter; `verify.sh` check 16, asserted via a Loki query on the tool's log line, not the model's response text, 2026-11-18 run | Verified |
| 00 (stack-check) | Traces, metrics and logs for the service reach the Grafana LGTM stack over OTLP from the first example that touches the network | `verify.sh` checks 13-15 (Tempo trace, Prometheus metric, Loki log line for `service.name=stack-check`), 2026-11-18 run | Verified |
