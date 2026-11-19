# 17 Event-triggered agent wakeup

A shipping-service that proves chapter 17's thesis: an agent that reacts to an event the instant
it arrives needs no poll loop. A Camel Kafka consumer (`PaymentWakeupRoute`, `groupId
shipping-wakeup`, `auto-offset-reset=earliest`) listens on `apa.payments.processed`. Each
`Payment` record that arrives (published unchanged, the same way 00-stack-check publishes
`Order` unchanged on `apa.orders.placed`) is handed to `AgentWakeupBean`, a thin
`@ActivateRequestContext` CDI bridge, which calls `ShipmentAdvisor`, a local langchain4j
`AiService` backed directly by `quarkus-langchain4j-ollama` (never the Camel `langchain4j-agent`
component). `ShipmentAdvisor` calls one `@Tool`, `ShipmentPrepTool.prepShipment(orderId,
carrier)`, which writes a durable record to the schema-qualified `shipping.shipment_prep` table,
increments a Micrometer counter and logs a line.

## MODEL=off: the fixture lane

`agent.wakeup.model-source` picks the decision path. `ollama` (the default, and what compose and
the live `verify.sh` always use) lets the real model decide and call the tool itself. `fixture`
(forced by the `%test` profile, so `mvn -B verify` and CI never need a model) routes the same
`Payment` to `FixtureAdvisor`, which replays a recorded decision from the committed
`src/main/resources/fixtures/wakeup-decisions.jsonl` instead of calling Ollama, falling back to
the same amount-based rule `ShipmentAdvisor`'s system prompt states (AIRCO at 100.00 or more,
otherwise FASTLINE) for any order id the fixture does not name. Either path ends at the same
`ShipmentPrepTool.prepShipment`, so the durable record, the counter and the log line this
chapter's checks assert on are identical whichever path produced the decision. This mirrors the
event-driven-patterns-with-quarkus-and-camel sibling's chapter 24 record/replay discipline.

## The negative witness

The witness is "a stopped consumer leaves the event where it fell": `wakeup.consumer.enabled`
(demo code, `WAKEUP_CONSUMER_ENABLED` in compose) stops `PaymentWakeupRoute` entirely when set to
`false`. `verify.sh` publishes a payment-processed event while the consumer is down and checks
that no `shipment_prep` row is written, then restores the consumer and checks that it reads the
event it missed (the group's committed offset never advanced while it was down, so nothing is
lost, only delayed). The row is the witness, not a point-in-time Prometheus query:
`ShipmentPrepTool.prepShipment` increments the counter and writes the row in the same method
call, so an absent row is already conclusive proof the counter never incremented either; a
Prometheus metric-name check would pass vacuously, since Prometheus keeps the previous container
instance's last-exported, nonzero sample live in its TSDB well past the restart.

To confirm that check actually discriminates, `PaymentWakeupRoute.configure` was changed by hand
to ignore the `wakeup.consumer.enabled` property (hardcoding `.autoStartup(true)`). With the
guard gone, a fresh `./verify.sh` run still consumed the event published while "disabled": the
negative-witness check failed (13/14), because a `shipment_prep` row existed for the order that
should have sat unconsumed. The hardcode was reverted and the suite reverified green (14/14). See
`_plans/verification-log.md` for the exact run.

## Run

```bash
../../scripts/ollama-model.sh check   # or pull, the first time
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it builds the service and the shared
domain model inside the image, starts Kafka, PostgreSQL, Apicurio, Keycloak, Ollama and LGTM plus
the service, runs the checks below (including the negative witness) and removes everything with
`down -v`. Set `KEEP=1` to leave the stack running for inspection.

Run the tests alone (Quarkus Dev Services starts Kafka and PostgreSQL containers; no compose
stack and no Ollama needed, since the `%test` profile forces the fixture decision path). Install
the shared domain model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Expected output

```
PASS 1: apa-kafka is healthy
...
PASS 7: apa-event-triggered-agent-wakeup is healthy
PASS 8: the woken agent records a shipment prep for O-7001 (observed in PostgreSQL, not model prose)
PASS 9: Tempo has a trace for service event-triggered-agent-wakeup
PASS 10: Prometheus has a metric from event-triggered-agent-wakeup
PASS 11: Prometheus has the prepShipment tool-call counter
PASS 12: Loki has the tool's own log line (observed, not model prose)
PASS 13: negative witness: a disabled consumer leaves the event unconsumed (no shipment_prep row for O-7002, so the counter in ShipmentPrepTool.prepShipment was never incremented either)
PASS 14: restored: the consumer that was disabled now records O-7002's shipment prep
14/14 checks passed
```

## Tests and checks in detail

`PaymentWakeupTest` (`mvn -B verify`) exercises the whole trigger-to-tool path against Dev
Services Kafka and PostgreSQL, MODEL=off: an order named in the committed fixture is recorded
with the fixture's carrier even when the amount-based rule would disagree (proof the fixture is
actually read, not just the rule); an order the fixture does not name falls back to the rule in
both directions (AIRCO at 100.00 or more, FASTLINE under it); an order never published has no
record.

`verify.sh` adds the live, real-model view: the same trigger-to-tool round trip through the real
`ShipmentAdvisor` and Ollama, OTel signals reaching Tempo, Prometheus and Loki, and the negative
witness, container-level, with the consumer actually stopped and restored.

## Experiments

Each row changes a single thing and names the checks that fail.

| Switch | Effect | Failing checks |
|---|---|---|
| `WAKEUP_CONSUMER_ENABLED=false` (left set, the route never restored) | Every payment-processed event sits unconsumed indefinitely | 8-12 (nothing is ever recorded) |
| Hardcode `.autoStartup(true)` in `PaymentWakeupRoute`, ignoring `wakeup.consumer.enabled` | The "disabled" consumer keeps consuming | 13 (the negative witness, the bite recorded in `_plans/verification-log.md`) |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../PaymentWakeupRoute.java` | the Camel Kafka consumer, the trigger; `wakeup.consumer.enabled` stops it |
| `src/main/java/.../AgentWakeupBean.java` | the `@ActivateRequestContext` CDI bridge; `agent.wakeup.model-source` picks ollama or fixture |
| `src/main/java/.../ShipmentAdvisor.java` | the langchain4j `AiService` interface (quarkus-langchain4j-ollama), registered with the one tool |
| `src/main/java/.../FixtureAdvisor.java` | the MODEL=off decision path: reads `fixtures/wakeup-decisions.jsonl`, falls back to the amount rule |
| `src/main/java/.../ShipmentPrepTool.java` | the one `@Tool`: the durable write, the Micrometer counter, the log line |
| `src/main/resources/fixtures/wakeup-decisions.jsonl` | the committed, recorded decisions `FixtureAdvisor` replays |
| `src/main/resources/db/migration/` | Flyway migration for `shipping.shipment_prep` |
| `compose.yaml` | includes the shared infrastructure and adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
