# 03 Intent endpoint

An order-service that proves chapter 03's thesis: one intent-named operation beats a chatty
CRUD sequence, because it can be made atomic. `POST /orders/{id}/cancel-and-refund` (body
`{"reason": "..."}`) is the agent-facing surface, served by Quarkus REST (JAX-RS), not Camel
`platform-http`. Camel stays on the event/EIP edge: the REST resource invokes a `direct:`
route that runs the cancel-and-refund saga and then publishes the resulting `OrderCancelled`
event to Kafka, keyed by order id.

The saga (`CancelRefundSaga`) moves `Order.status` to `CANCELLED` and `Payment.status` to
`REFUNDED` in one `@Transactional` method, against the `orders` and `payments` schemas of the
same `apa` database (ch03 spans both). Either both move, or neither does.

## The negative witness

A chatty, CRUD-shaped caller would cancel the order with one call and refund the payment with
a second, separate call. Between those two calls there is a window where the order is
`CANCELLED` and the payment is still `COMPLETED`: partial state an agent (or a human) could
observe and act on incorrectly. `verify.sh`'s "no partial state is observable" check reads
both rows in one query and asserts they always read `CANCELLED|REFUNDED` together.

To confirm that check actually discriminates (and is not vacuously true), the atomicity guard
was removed by hand: `@Transactional` was deleted from `CancelRefundSaga.cancelAndRefund` and
a `throw new RuntimeException(...)` was inserted between the order update and the payment
update. With the guard gone, the order update auto-commits before the injected failure stops
the method, so the payment is never refunded. `mvn -B verify` then failed the
`cancelAndRefundFlipsBothAndPublishesThenRejectsDoubleCancel` test, and a live `./verify.sh`
failed at the "no partial state is observable" check. Both edits were reverted and the suite
reverified green. See `_plans/verification-log.md` for the exact run.

## Run

```bash
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it builds the service and the shared
domain model inside the image, starts Kafka, PostgreSQL, Apicurio, Keycloak, Ollama and LGTM
plus the service, runs the checks below and removes everything with `down -v`. Set `KEEP=1`
to leave the stack running for inspection.

Run the tests alone (Quarkus Dev Services starts Kafka and PostgreSQL containers; no compose
stack needed). Install the shared domain model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Expected output

```
PASS 1: apa-kafka is healthy
...
PASS 7: apa-intent-endpoint is healthy
PASS 8: the seeded order O-5001 starts PLACED
PASS 9: the seeded payment for O-5001 starts COMPLETED
PASS 10: one POST to cancel-and-refund succeeds
PASS 11: the order flips to CANCELLED
PASS 12: the payment flips to REFUNDED
PASS 13: no partial state is observable: order and payment moved together
PASS 14: exactly one OrderCancelled record keyed by O-5001 on apa.orders.cancelled
PASS 15: negative witness: cancelling an already-cancelled order is rejected, not re-run
PASS 16: negative witness: cancelling an unknown order is rejected
16/16 checks passed
```

## Tests and checks in detail

`IntentEndpointTest` (`mvn -B verify`) exercises the REST resource directly against Dev
Services Kafka and PostgreSQL: cancelling the seeded order flips both rows, publishes exactly
one `OrderCancelled` record keyed by the order id, and a second cancel attempt on the same
order is rejected with 409; cancelling an unknown order id is rejected with 404.

`verify.sh` adds the live, container-level view: every infrastructure container healthy, the
seeded starting state, the same flip-and-publish behaviour observed directly in PostgreSQL and
Kafka, and the "no partial state" assertion that is this chapter's point.

## Experiments

Each row changes a single thing and names the checks that fail.

| Switch | Effect | Failing checks |
|---|---|---|
| Remove `@Transactional` from `CancelRefundSaga.cancelAndRefund` and throw after the order update, before the payment update | The order update commits alone; the payment is never refunded | 13 (partial state becomes observable) |
| Change `Topics.ORDERS_CANCELLED` in the route to a different topic string | No record arrives on `apa.orders.cancelled` | 14 |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../OrderResource.java` | the agent-facing Quarkus REST surface: `POST /orders/{id}/cancel-and-refund` |
| `src/main/java/.../CancelRefundRoute.java` | the Camel route: runs the saga, then publishes `OrderCancelled` keyed by order id |
| `src/main/java/.../CancelRefundSaga.java` | the `@Transactional` atomic saga against the `orders` and `payments` schemas |
| `src/main/java/.../OrderCancelledEvent.java` | the event payload (per-example, not in domain-model) |
| `src/main/java/.../OrderNotFoundException.java`, `OrderNotCancellableException.java` | the two failure modes, mapped to 404 and 409 |
| `src/main/resources/db/migration/` | Flyway migrations for the `orders` and `payments` schemas, plus the seed |
| `compose.yaml` | includes the shared infrastructure and adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
