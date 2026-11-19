# 20 Long-running agent workflow

A service that proves chapter 20's thesis: a long-running, agent-triggered process runs as steps
with compensating actions, so a failure partway through unwinds to a consistent state instead of
leaving a partial effect. `POST /orders` starts an order-fulfillment saga, written with the Apache
Camel Saga EIP: reserve stock, take payment, then have an agent choose and book a carrier. Each
step is wrapped in its own nested `.saga()` block with its own `.compensation(...)` endpoint
(`releaseStock`, `refundPayment`, `cancelBooking`); the outer `.saga()` owns the saga's lifecycle
(propagation `REQUIRES_NEW`, a `fulfillment.saga.timeout`) and completion mode stays at its
default, `AUTO`: the saga completes if every step returns normally, and compensates every step
enlisted so far, in reverse order, the moment any step throws.

The coordinator is Camel's in-memory `CamelSagaService`
(`org.apache.camel.saga.InMemorySagaService`, shipped in `camel-support`), registered once by
`InMemorySagaRegistration` and used unconditionally, in the live compose run and in `mvn -B
verify` alike: no external LRA coordinator, no Narayana. The carrier-choice step is a local
langchain4j `AiService` backed directly by `quarkus-langchain4j-ollama` (never the Camel
`langchain4j-agent` component), the same direct-call shape 17-event-triggered-agent-wakeup uses.

## MODEL=off: the fixture lane

`agent.carrier.model-source` picks the decision path. `ollama` (the default, and what compose and
the live `verify.sh` always use) lets the real model decide and call `CarrierBookingTool.bookCarrier`
itself. `fixture` (forced by the `%test` profile, so `mvn -B verify` and CI never need a model)
routes the same order to `FixtureCarrierAdvisor`, which replays a recorded decision from the
committed `src/main/resources/fixtures/carrier-decisions.jsonl` instead of calling Ollama, falling
back to the same amount-based rule `CarrierAdvisor`'s system prompt states (AIRCO at 100.00 or
more, otherwise FASTLINE) for any order id the fixture does not name. Either path ends at the same
`CarrierBookingTool.bookCarrier`, so the durable record, the counter and the log line this
chapter's checks assert on are identical whichever path produced the decision.

## Idempotent compensation

Every compensation method in `FulfillmentService` is a single guarded `UPDATE` that only takes
effect from the exact prior state it undoes (for example `releaseStock` only restores stock from a
row it finds in status `STOCK_RESERVED`), the same idempotent-by-guard idiom the
event-driven-patterns-with-quarkus-and-camel sibling's chapter 22 saga uses for its stock release.
Calling a compensation twice, which Camel can do if a saga is retried, changes nothing the second
time; `OrderFulfillmentSagaTest` exercises all three compensations this way directly.

## The negative witness

The witness is "a disabled compensation leaves the reserved stock reserved":
`fulfillment.compensation.enabled` (demo code, `COMPENSATE` in compose) turns every compensation
method in `FulfillmentService` into a no-op that only logs, when set to `false`. `verify.sh` places
an order whose amount is declined by the payment step while compensation is disabled and checks
that the stock `reserveStock` took is never given back (a partial effect survives the failure),
then restores compensation and checks that a further declined order is compensated correctly
again.

To confirm that check actually discriminates, `FulfillmentService.releaseStock` was changed by
hand to return immediately, unconditionally (ignoring `fulfillment.compensation.enabled`
entirely). A fresh `./verify.sh` run still restored the stock after the declined payment: the
negative-witness check failed, because the stock had not stayed reserved as it should have while
compensation was explicitly disabled. The hand-edit was reverted and the suite reverified green.
See `_plans/verification-log.md` for the exact run.

## Run

```bash
../../scripts/ollama-model.sh check   # or pull, the first time
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it builds the service and the shared
domain model inside the image, starts Kafka, PostgreSQL, Apicurio, Keycloak, Ollama and LGTM plus
the service (Kafka, Apicurio and Keycloak come up as shared infrastructure but this chapter's
service does not use them), runs the checks below (including the negative witness) and removes
everything with `down -v`. Set `KEEP=1` to leave the stack running for inspection.

Run the tests alone (Quarkus Dev Services starts PostgreSQL from a container; no compose stack and
no Ollama needed, since the `%test` profile forces the fixture decision path). Install the shared
domain model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Expected output

```
PASS 1: apa-kafka is healthy
...
PASS 7: apa-long-running-agent-workflow is healthy
PASS 8: O-20101 completes (reserve, pay, agent books a carrier)
PASS 9: O-20101 took one unit of SKU-20-A and kept it (no release on the happy path)
PASS 10: O-20101's payment is COMPLETED
PASS 11: O-20101's shipment was booked with a valid carrier (FASTLINE or AIRCO), not asserted by prose
PASS 12: O-20102's payment is declined and the saga compensates
PASS 13: O-20102's stock is back where it was before the reservation (no net change)
PASS 14: O-20102 has no payment row (the declined payment wrote nothing)
PASS 15: O-20102 has no shipment row (the carrier step never ran)
PASS 16: Tempo has a trace for service long-running-agent-workflow
PASS 17: Prometheus has a metric from long-running-agent-workflow
PASS 18: Prometheus has the bookCarrier tool-call counter
PASS 19: Loki has the tool's own log line (observed, not model prose)
PASS 20: O-20103's payment is still declined
PASS 21: negative witness: with compensation disabled, O-20103's reserved stock stays reserved (a partial effect)
PASS 22: restored: O-20104's declined payment is compensated again
PASS 23: restored: O-20104's stock is back where it was before the reservation
23/23 checks passed
```

## Tests and checks in detail

`OrderFulfillmentSagaTest` (`mvn -B verify`) exercises the whole saga against Dev Services
PostgreSQL, MODEL=off: a clean run completes every step and books a carrier; an order named in the
committed fixture is booked with the fixture's carrier even when the amount-based rule would
disagree (proof the fixture is actually read); a declined payment compensates the stock
reservation with no net change, writes no payment and no shipment row; an order asking for more
stock than exists compensates with nothing to undo; and all three compensation methods are called
twice directly to prove they are idempotent. `CompensationDisabledTest` reproduces the negative
witness at the unit level with `fulfillment.compensation.enabled=false`.

`verify.sh` adds the live, real-model view: the same saga through the real `CarrierAdvisor` and
Ollama, OTel signals reaching Tempo, Prometheus and Loki, and the negative witness,
container-level, with the compensation guard actually disabled and restored.

## Experiments

Each row changes a single thing and names the checks that fail.

| Switch | Effect | Failing checks |
|---|---|---|
| `PAYMENT_DECLINE_ABOVE=0.00` | Every order's payment is declined | 8-11, 16-19 (nothing ever completes, so nothing is ever booked) |
| `COMPENSATE=off` (left set, compensation never restored) | Every declined payment's reservation stays reserved indefinitely | 21 only while set; restoring it is what checks 22-23 confirm |
| Hand-edit `FulfillmentService.releaseStock` to return immediately | The "disabled" compensation path cannot be told apart from a working one that has nothing to undo | 21 (the negative witness, the bite recorded in `_plans/verification-log.md`) |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../OrderFulfillmentSagaRoute.java` | the saga: one outer `.saga()` block and three nested ones, each with its own `.compensation(...)` |
| `src/main/java/.../InMemorySagaRegistration.java` | registers Camel's in-memory `CamelSagaService`, the only coordinator this example uses |
| `src/main/java/.../FulfillmentService.java` | the three steps and their three compensations, each compensation a guarded, idempotent `UPDATE` |
| `src/main/java/.../CarrierDecisionBean.java` | the `@ActivateRequestContext` CDI bridge; `agent.carrier.model-source` picks ollama or fixture |
| `src/main/java/.../CarrierAdvisor.java` | the langchain4j `AiService` interface (quarkus-langchain4j-ollama), registered with the one tool |
| `src/main/java/.../FixtureCarrierAdvisor.java` | the MODEL=off decision path: reads `fixtures/carrier-decisions.jsonl`, falls back to the amount rule |
| `src/main/java/.../CarrierBookingTool.java` | the one `@Tool`: the durable write, the Micrometer counter, the log line |
| `src/main/java/.../OrderFulfillmentResource.java` | `POST /orders` starts the saga; `GET /orders/{id}` reads its current status |
| `src/main/resources/fixtures/carrier-decisions.jsonl` | the committed, recorded decisions `FixtureCarrierAdvisor` replays |
| `src/main/resources/db/migration/` | Flyway migration for `orders.order_fulfillment`, `inventory.inventory_item`, `payments.payment`, `shipping.shipment` |
| `contracts/20-saga-topology.md` | the steps, their compensations and the saga's shape (no topic: the trigger and the saga are purely in-process HTTP) |
| `compose.yaml` | includes the shared infrastructure and adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
