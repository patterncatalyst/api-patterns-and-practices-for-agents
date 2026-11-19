# 10 Instructive error (RFC 9457 problem+json)

An order-service that proves chapter 10's thesis: an error an agent can act on names what is
wrong and what to do about it, not just a status code. Three domain exceptions map to three
distinct `application/problem+json` bodies through `@ServerExceptionMapper`, each carrying the
four RFC 9457 members (`type`, `title`, `status`, `detail`) plus a remedy member specific to that
failure: `OrderNotFoundException` to 404 (`nextStep`), `OrderNotCancellableException` to 409
(`currentState` and `allowedTransitions`, computed from `OrderStatus.canMoveTo`), and
`OrderValidationException` to 400 (`field`, the offending one). `POST /orders` (`placeOrder`),
`GET /orders/{id}` (`getOrder`) and `POST /orders/{id}/cancel` (`cancelOrder`) are served by
Quarkus REST; Camel stays on the event edge, publishing `OrderPlaced` and `OrderCancelled`.
`OrderResource` never builds an error response itself beyond unwrapping Camel's
`CamelExecutionException`: every domain exception propagates to `OrderExceptionMappers`, the
single place each error's shape is decided.

## The three problem shapes

```json
{"type":"https://errors.patterncatalyst.dev/problems/order-not-found","title":"Order not found",
 "status":404,"detail":"no order with id O-NOPE",
 "nextStep":"place a new order with POST /orders, then retry with the orderId that call returns"}

{"type":"https://errors.patterncatalyst.dev/problems/order-validation","title":"Order request is invalid",
 "status":400,"detail":"quantity must be positive","field":"quantity"}

{"type":"https://errors.patterncatalyst.dev/problems/order-not-cancellable","title":"Order not cancellable",
 "status":409,"detail":"order O-2002 cannot be cancelled from status SHIPPED",
 "currentState":"SHIPPED","allowedTransitions":["DELIVERED"]}
```

A seeded order (`O-2002`, status `SHIPPED`) demonstrates the 409 with a non-empty remedy
(`allowedTransitions: ["DELIVERED"]`); cancelling an order a second time demonstrates the
terminal case (`currentState: "CANCELLED"`, `allowedTransitions: []`).

## The negative witness

The witness is "the `@ServerExceptionMapper` methods are what put the content type and the
remedy in the response; RESTEasy Reactive does not infer either from a plain `RuntimeException`."
To confirm, all three `@ServerExceptionMapper` annotations in `OrderExceptionMappers` were
commented out. With the mappers gone, `mvn -B verify` failed 6 of 7 tests (every error case
returned 500 instead of its mapped status). A live `./verify.sh` run fell to 9/15, failing every
check that reads a problem body (the not-found, both validation and both conflict checks, plus
the distinct-types check); a direct `curl` against the broken build showed `500`,
`content-type: text/plain`, and a generic "Internal Server Error" body with no `type`, no
`detail` and no remedy member at all. The annotations were restored and both reverified green
(`mvn -B verify` 7/7, `./verify.sh` 15/15), reproducing the same three problem bodies.

## Run

```bash
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin and `jq`; it builds the service and the
shared domain model inside the image, starts Kafka, PostgreSQL, Apicurio, Keycloak, Ollama and
LGTM plus the service, runs the checks below and removes everything with `down -v`. Set `KEEP=1`
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
PASS 7: apa-instructive-error is healthy
PASS 8: placing an order succeeds with 201
PASS 9: an unknown order id is an instructive 404 problem+json with a nextStep remedy
PASS 10: an invalid quantity is an instructive 400 problem+json naming the quantity field
PASS 11: a blank customerId is an instructive 400 problem+json naming the customerId field
PASS 12: cancelling the placed order succeeds
PASS 13: cancelling it again is an instructive 409 naming CANCELLED and no allowed transitions
PASS 14: cancelling the seeded SHIPPED order names DELIVERED as the only allowed transition
PASS 15: the three problem types (404, 400, 409) are distinct
15/15 checks passed
```

## Tests and checks in detail

`InstructiveErrorTest` (`mvn -B verify`) exercises `OrderResource`, the two Camel routes and
`OrderService` against Dev Services Kafka and PostgreSQL: an unknown order id returns an
instructive 404; an invalid quantity and a blank `customerId` each return an instructive 400
naming the offending field; cancelling a freshly placed order succeeds, a second cancel on the
same order returns an instructive 409 naming `CANCELLED` with no allowed transitions; and the
seeded `SHIPPED` order's cancel attempt names `DELIVERED` as the only allowed transition.

`verify.sh` adds the live, container-level view of the same contract, plus the direct
RFC 9457 member checks against the running service.

## Experiments

Each row changes a single thing and names the checks that fail.

| Switch | Effect | Failing checks |
|---|---|---|
| Comment out `@ServerExceptionMapper` on all three mapper methods | Every domain exception falls through to a bare 500, `text/plain` | 9, 10, 11, 13, 14, 15 |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../OrderResource.java` | the agent-facing Quarkus REST surface: `placeOrder`, `getOrder`, `cancelOrder` |
| `src/main/java/.../OrderExceptionMappers.java` | the three `@ServerExceptionMapper` methods |
| `src/main/java/.../NotFoundProblem.java`, `ConflictProblem.java`, `ValidationProblem.java` | the three distinct RFC 9457 problem shapes |
| `src/main/java/.../OrderNotFoundException.java`, `OrderNotCancellableException.java`, `OrderValidationException.java` | the three domain exceptions |
| `src/main/java/.../PlaceOrderRoute.java`, `CancelOrderRoute.java` | the Camel event edge: write, then publish |
| `src/main/java/.../OrderService.java` | the `@Transactional` order writes and reads against `orders.orders` |
| `src/main/resources/db/migration/` | Flyway migration and the seeded `SHIPPED` order for the 409 demonstration |
| `compose.yaml` | includes the shared infrastructure and adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
