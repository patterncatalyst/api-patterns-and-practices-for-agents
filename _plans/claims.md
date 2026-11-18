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
| 01 (why-agent-apis) | Given the same task ("cancel order O-1007 and refund the customer") and the same model, a granular, CRUD-ish tool contract (`getOrder`, `updateOrderStatus`, `createRefund`) costs more tool calls and more tokens than a single intent tool (`cancelAndRefund(orderId, reason)`): 5 calls and 3185 tokens versus 1 call and 759 tokens | `fixtures/good-run.json`, `fixtures/bad-run.json`, recorded once against `qwen3:4b-instruct-2507-q4_K_M` (`temperature=0`, `seed=42`); `verify.sh` checks 1, 2, 4 and 5, 2026-11-18 replay (6/6) and LIVE run (10/10, same numbers) | Verified |
| 01 (why-agent-apis) | Opaque, no-remedy errors on the granular contract (a bare `{"error":"E400"}` for a guessed refund amount and for a tool call missing a required `orderId`) can cause an agent to abandon the task outright, leaving the customer unrefunded, even though the single-intent contract completes the same task correctly in one call | `fixtures/bad-run.json` (`argErrors: 2`, `refundCompleted: false`, full tool trace and the model's own "cannot be fulfilled" reply) versus `fixtures/good-run.json` (`refundCompleted: true`); `verify.sh` check 3, 2026-11-18 replay and LIVE run | Verified |
| 01 (why-agent-apis) | A pass rule written for the good contract (the task always completes in exactly one tool call) is not vacuous: it actually fails when applied to the bad contract's own recorded run | `verify.sh` check 6 (negative witness), 2026-11-18; confirmed by mutation: editing `fixtures/bad-run.json` to claim `toolCalls:1` makes checks 4 and 6 fail (4/6), fixture then restored and reverified at 6/6 | Verified |
| 00 (prerequisites and the two stacks) | The application stack (Kafka, PostgreSQL, Apicurio, Keycloak, Ollama) and the observability stack (Grafana LGTM) are two separate compose files, started and stopped independently; the stack-check example brings the observability stack up to confirm signals arrive, and otherwise it stays optional | `examples/_infra/compose.yaml`, `examples/_infra/compose.lgtm.yaml`, reviewed 2026-11-18 | Verified |
| 00 (prerequisites and the two stacks) | Every version pin stated in the chapter's table (JDK, Maven, Quarkus platform, Camel, Kafka, PostgreSQL, Apicurio, Keycloak, Ollama, model, UBI10 build and runtime images) matches the re-surveyed pin recorded in `_plans/versions.md` | `_plans/versions.md`, re-surveyed 2026-11-18 against each pin's own upstream source | Verified |
| 00 (prerequisites and the two stacks) | Apache Maven 3.10.1 is the current overall stable release; the host pin is held at 3.9.16 on an open Quarkus-reported incompatibility, matching the event-driven-patterns-with-quarkus-and-camel sibling's hold | `maven.apache.org/download.cgi`, fetched 2026-11-18; `_plans/versions.md` | Verified |
| 01 (why agent-facing APIs are different) | The lethal trifecta names private data, untrusted content and a path for that data to leave, combined, as the exfiltration risk an agent-facing tool surface must not create | Simon Willison, "The lethal trifecta for AI agents", 2025-06-16, fetched 2026-11-18; `bibliography.md` | Verified |
| 02 (how to read this catalog) | The Model Context Protocol is governed as "Model Context Protocol, a Series of LF Projects, LLC" under the Linux Foundation, not any one vendor | `modelcontextprotocol.io/community/governance`, fetched 2026-11-18 | Verified |
| 02 (how to read this catalog) | Chip Huyen's *AI Engineering* was published by O'Reilly in 2024, ISBN 978-1-098-16630-4 | Open Library record, fetched 2026-11-18; `bibliography.md` | Verified |
