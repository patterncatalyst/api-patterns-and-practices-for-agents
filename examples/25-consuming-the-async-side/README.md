# 25 Consuming the async side

A notification service that proves this chapter's thesis: an agent CONSUMING the async side of an
agent-facing API surface needs no poll loop, and it must act on the event idempotently. A Camel
Kafka consumer (`ShipmentDeliveredRoute`, group `consuming-the-async-side`,
`auto-offset-reset=earliest`) listens on `apa.shipping.delivered`. Each `ShipmentDelivered` event
that arrives (this chapter's own event payload record, carrying the order id, the customer to
notify, the carrier and the tracking number) is handed to `NotificationBean`, a thin
`@ActivateRequestContext` CDI bridge, which calls `NotificationComposerAgent`, a local langchain4j
`AiService` backed directly by `quarkus-langchain4j-ollama` (never the Camel `langchain4j-agent`
component), to compose the customer-facing message. `NotificationRecorder` then writes a durable
`notify.notifications` row keyed on `(order_id, event_type)`, increments a Micrometer counter and
logs a line.

The structured fields of that row, the order id, the customer id and the event type
(`SHIPMENT_DELIVERED`), come straight from the consumed event, carried in a `Notification` (the
shared `domain-model` record) that `NotificationBean` builds itself. Only the message text comes
from the model, and it is stored but never asserted on: qwen3's composed prose is nondeterministic,
so this chapter's checks key on the event-derived columns and the row's presence or absence, never
on what the agent wrote.

## MODEL=off: the fixture lane

`agent.notification.model-source` picks the message path. `ollama` (the default, and what compose
and the live `verify.sh` always use) lets the real model compose the notification text.
`fixture` (forced by the `%test` profile, so `mvn -B verify` and CI never need a model) routes to
`FixtureComposer`, which composes a deterministic templated message instead of calling Ollama.
Either path ends at the same `NotificationRecorder.record`, so the durable record, the counter and
the log line this chapter's checks assert on are identical whichever path produced the message.
This mirrors 17-event-triggered-agent-wakeup's `FixtureAdvisor`/`ShipmentPrepTool` record/replay
discipline.

## The idempotency witness

`notify.notifications` has a composite primary key, `(order_id, event_type)`, and
`NotificationRecorder.record` inserts with `ON CONFLICT (order_id, event_type) DO NOTHING`. The
same `ShipmentDelivered` event redelivered, whether from a consumer group rebalance or an
at-least-once producer retry, carries the same order id and so the same key: the second delivery
can add no second row. `ShipmentDeliveredConsumerTest.redeliveringTheSameEventStaysAtExactlyOneRow`
publishes the same event twice and asserts the row count stays at one; `verify.sh` repeats the same
check, container-level, against the live stack.

## The negative witness

The witness is "a stopped consumer leaves the event where it fell": `consumer.async.enabled`
(demo code, `CONSUMER_ASYNC_ENABLED` in compose) stops `ShipmentDeliveredRoute` entirely when set
to `false`. `verify.sh` publishes a delivered event while the consumer is down and checks that no
`notify.notifications` row is written, then restores the consumer and checks that it reads the
event it missed (the group's committed offset never advanced while it was down, so nothing is
lost, only delayed). The row is the witness, not a point-in-time Prometheus query:
`NotificationRecorder.record` increments the counter and writes the row in the same method call,
so an absent row is already conclusive proof the counter never incremented either; a Prometheus
metric-name check would pass vacuously, since Prometheus keeps the previous container instance's
last-exported, nonzero sample live in its TSDB well past the restart.

## Run

```bash
../../scripts/ollama-model.sh check   # or pull, the first time
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it builds the service and the shared
domain model inside the image, starts Kafka, PostgreSQL, Apicurio, Keycloak, Ollama and LGTM plus
the service, runs the checks below (including both witnesses) and removes everything with
`down -v`. Set `KEEP=1` to leave the stack running for inspection.

Run the tests alone (Quarkus Dev Services starts Kafka and PostgreSQL containers; no compose stack
and no Ollama needed, since the `%test` profile forces the fixture message path). Install the
shared domain model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Expected output

```
PASS 1: apa-kafka is healthy
...
PASS 7: apa-consuming-the-async-side is healthy
PASS 8: the woken agent records a notification for O-7501 (observed in PostgreSQL, not model prose)
PASS 9: Tempo has a trace for service consuming-the-async-side
PASS 10: Prometheus has a metric from consuming-the-async-side
PASS 11: Prometheus has the notification-recorded counter
PASS 12: Loki has the recorder's own log line (observed, not model prose)
PASS 13: the first delivery of O-7502 is recorded
PASS 14: idempotency witness: redelivering O-7502's event stays at exactly one notification row
PASS 15: negative witness: a disabled consumer leaves the event unconsumed (no notification row for O-7503, so the counter in NotificationRecorder.record was never incremented either)
PASS 16: restored: the consumer that was disabled now records O-7503's notification
16/16 checks passed
```

## Tests and checks in detail

`ShipmentDeliveredConsumerTest` (`mvn -B verify`) exercises the whole trigger-to-record path
against Dev Services Kafka and PostgreSQL, MODEL=off: a delivered event records one notification
with the event-derived customer id; the same event redelivered stays at exactly one row; an order
never delivered has no notification.

`verify.sh` adds the live, real-model view: the same trigger-to-record round trip through the real
`NotificationComposerAgent` and Ollama, OTel signals reaching Tempo, Prometheus and Loki, the
idempotency witness and the negative witness, container-level, with the consumer actually stopped
and restored.

## Experiments

Each row changes a single thing and names the checks that fail.

| Switch | Effect | Failing checks |
|---|---|---|
| `CONSUMER_ASYNC_ENABLED=false` (left set, the route never restored) | Every delivered event sits unconsumed indefinitely | 8, 13, 14 (nothing is ever recorded) |
| Hardcode `.autoStartup(true)` in `ShipmentDeliveredRoute`, ignoring `consumer.async.enabled` | The "disabled" consumer keeps consuming | 15 (the negative witness) |
| Drop `ON CONFLICT (order_id, event_type) DO NOTHING` from `NotificationRecorder.record` | Redelivery inserts a second row | 14 (the idempotency witness) |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../ShipmentDelivered.java` | the event payload record consumed on `apa.shipping.delivered` |
| `src/main/java/.../ShipmentDeliveredRoute.java` | the Camel Kafka consumer, the trigger; `consumer.async.enabled` stops it |
| `src/main/java/.../NotificationBean.java` | the `@ActivateRequestContext` CDI bridge; `agent.notification.model-source` picks ollama or fixture; builds the event-derived `Notification` |
| `src/main/java/.../NotificationComposerAgent.java` | the langchain4j `AiService` interface (quarkus-langchain4j-ollama), composes the message, no tools |
| `src/main/java/.../FixtureComposer.java` | the MODEL=off message path: a deterministic templated message |
| `src/main/java/.../NotificationRecorder.java` | the durable write (idempotent on `(order_id, event_type)`), the Micrometer counter, the log line |
| `src/main/resources/db/migration/` | Flyway migration for `notify.notifications` |
| `compose.yaml` | includes the shared infrastructure and adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
