# 06 Idempotency and safe retries

An order-service that proves chapter 06's thesis: a safe retry needs a server-side guarantee,
not caller discipline. `POST /orders` (body `{"customerId", "itemSku", "quantity", "amount"}`)
takes a required `Idempotency-Key` header, served by Quarkus REST (JAX-RS), not Camel
`platform-http`. Camel stays on the event/EIP edge: the REST resource invokes a `direct:` route
that runs the Idempotent Consumer EIP, backed by a JDBC idempotent repository
(`camel-quarkus-sql`'s `JdbcMessageIdRepository`), keyed on that header.

A first-time key runs `PlaceOrderSaga.placeNewOrder`: one `Order` row (schema `orders`) and one
stored response are written in a single `@Transactional` method, then the route publishes one
`OrderPlaced` event to `apa.orders.placed`, keyed by order id. A repeat key never runs the saga
again: `PlaceOrderSaga.replayOrReject` replays the first stored response, byte for byte, if the
new request's body matches the one that was stored; if the body differs, it rejects the call
with `application/problem+json` and 409, rather than silently replaying the wrong answer or
quietly placing a second order.

## The negative witness

The witness is "a repeat key adds nothing": `verify.sh` sends the same `Idempotency-Key` twice
with the same body and checks that the `orders.orders` row count and the `apa.orders.placed`
topic offset for that order id both stay at exactly one after the second call, and that the
second response is byte-identical to the first.

To confirm that check actually discriminates, the Idempotent Consumer EIP was removed from
`PlaceOrderRoute.configure` by hand: the route was changed to call `saga.placeNewOrder`
unconditionally, skipping the `.idempotentConsumer(...)` wrapper entirely. With the guard gone,
two POSTs carrying the same `Idempotency-Key` and the same body each ran the saga fully: two
`Order` rows were written (each saga call generates a fresh order id, so no DB constraint
collides) and two `OrderPlaced` records reached the topic. `mvn -B verify` then failed
`twoCallsWithTheSameKeyAndBodyYieldOneOrderOneEventAndAnIdenticalResponse` (two different order
ids and response bodies where one was expected), and a live `./verify.sh` failed the "Order row
count is unchanged" and "exactly one OrderPlaced record" checks. Both were reverted and the
suite reverified green. See `_plans/verification-log.md` for the exact run.

## Same key, different body

A repeat `Idempotency-Key` is only ever a safe retry of the *same* request. `PlaceOrderSaga`
hashes the request's fields (not the raw JSON bytes, so field order or whitespace does not
matter) and stores the hash alongside the first response. A second call with the same key and a
different hash is rejected with 409 and an instructive `application/problem+json` body naming
the order the key was already used for, instead of replaying a response that does not match
what the caller just asked for.

## Run

```bash
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin and `jq`; it builds the service and the
shared domain model inside the image, starts Kafka, PostgreSQL, Apicurio, Keycloak, Ollama and
LGTM plus the service, runs the checks below and removes everything with `down -v`. Set
`KEEP=1` to leave the stack running for inspection.

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
PASS 7: apa-idempotency-safe-retries is healthy
PASS 8: a POST with a missing Idempotency-Key is rejected with 400
PASS 9: the first POST with a new Idempotency-Key succeeds with 201
PASS 10: exactly one Order row exists after the first call
PASS 11: a second POST with the same key and the same body is replayed, byte-identical
PASS 12: the Order row count is unchanged by the replay
PASS 13: exactly one OrderPlaced record keyed by the order id reached apa.orders.placed
PASS 14: a different key's first POST succeeds
PASS 15: two distinct keys now account for exactly two Order rows
PASS 16: negative witness: the same key reused with a different body is rejected, not replayed
PASS 17: the Order row count is unchanged by the rejected conflict
PASS 18: the conflicting attempt published nothing new for its order id
18/18 checks passed
```

## Tests and checks in detail

`IdempotencySafeRetriesTest` (`mvn -B verify`) exercises `OrderResource`, `PlaceOrderRoute` and
`PlaceOrderSaga` against Dev Services Kafka and PostgreSQL: two calls with the same key and body
return an identical response and publish exactly one `OrderPlaced` record; the same key with a
different body is rejected with 409 and `application/problem+json`; a missing key is rejected
with 400; an invalid order (quantity 0) is rejected with 400 without locking the key.

`verify.sh` adds the live, container-level view: every infrastructure container healthy, the
same replay-is-identical and reject-on-conflict behaviour observed directly in PostgreSQL
(`orders.orders` row count) and Kafka (`apa.orders.placed` offset), for two independent keys.

## Experiments

Each row changes a single thing and names the checks that fail.

| Switch | Effect | Failing checks |
|---|---|---|
| Remove `.idempotentConsumer(...)` from `PlaceOrderRoute` so every call runs `placeNewOrder` unconditionally | A repeat key places a second order and publishes a second event instead of replaying | 12, 13 |
| Change `PlaceOrderSaga.hash` to always return the same constant | Two different request bodies under different keys would collide; not exercised by the committed checks, but breaks the "different body" rejection for any two orders that happen to share a key | none of the committed checks (the keys used here are always distinct across different bodies) |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../OrderResource.java` | the agent-facing Quarkus REST surface: `POST /orders` with the `Idempotency-Key` header |
| `src/main/java/.../PlaceOrderRoute.java` | the Camel route: the Idempotent Consumer EIP over a JDBC idempotent repository, then the Kafka publish on a new key only |
| `src/main/java/.../PlaceOrderSaga.java` | the `@Transactional` order-and-response write, and the replay/reject decision |
| `src/main/java/.../OrderCreateRequest.java`, `OrderPlacedEvent.java`, `OrderPlacedResponse.java`, `PlaceOrderResult.java`, `Problem.java` | per-example request, event and response shapes |
| `src/main/resources/db/migration/` | Flyway migration for `orders.orders`, the EIP's own `orders.idempotency_consumer` table, and `orders.idempotent_responses` |
| `compose.yaml` | includes the shared infrastructure and adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
