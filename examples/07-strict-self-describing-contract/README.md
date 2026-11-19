# 07 Strict self-describing contract

An order-service that proves chapter 07's thesis: a contract an agent can bind to without a
human reading it first has to be generated from the running code, not written by hand and hoped
to still match. `POST /orders` (`placeOrder`) and `GET /orders/{id}` (`getOrder`) are served by
Quarkus REST (JAX-RS); Camel stays on the event edge, publishing one `OrderPlaced` event per new
order. `quarkus-smallrye-openapi` generates the OpenAPI document from annotations on
`OrderResource` and its request/response records: every operation carries a stable
`operationId`, the `OrderCreateRequest` fields are declared `required`, the `OrderResponse`
`status` field enumerates all 5 `OrderStatus` values, and both error responses are
`application/problem+json` with a `Problem` schema, never a bare string or an opaque code.

## The generated contract

`/q/openapi` is served at runtime; the committed `contracts/07-order-service.openapi.yaml` is
that same document, fetched from a live run and checked in verbatim. `verify.sh` fetches the
served document again on every run and diffs it against the committed file after normalizing
both to sorted-key JSON (so key order and YAML-vs-JSON formatting never cause a false mismatch):
any drift between what the code generates today and what was committed fails the run. This is
the local half of the chapter's drift gate; the full CI version lands at chapter 32.

## The negative witness

The witness is "the annotations are what put the enum, the required fields and the stable
operationId in the contract; SmallRye does not infer them from the plain Java types." To confirm, the
`@Schema(enumeration = ...)` annotation was removed from `OrderResponse.status`, the
`@Schema(required = true)` annotations were removed from every `OrderCreateRequest` field, and
`operationId` was removed from both `@Operation` annotations on `OrderResource`. With the
annotations gone, `mvn -B verify` failed `openApiDocumentDeclaresTheStrictContract` (expected
`placeOrder`, got `null`), and a live `./verify.sh` run fell to 15/21, failing both
operationId checks, both enum checks, the required-fields check, and the normalized diff against
the committed contract (the regenerated document no longer matched what was checked in). All
three edits were reverted and the suite reverified green (`mvn -B verify` 6/6, `./verify.sh`
21/21), reproducing the same served document.

## Run

```bash
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin, `jq` and `python3` with PyYAML
installed; it builds the service and the shared domain model inside the image, starts Kafka,
PostgreSQL, Apicurio, Keycloak, Ollama and LGTM plus the service, runs the checks below and
removes everything with `down -v`. Set `KEEP=1` to leave the stack running for inspection.

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
PASS 7: apa-strict-self-describing-contract is healthy
PASS 8: placing an order succeeds with 201
PASS 9: getting the order just placed succeeds with 200
PASS 10: getting an unknown order id is rejected with 404 problem+json
PASS 11: placing an invalid order (quantity 0) is rejected with 400 problem+json
PASS 12: /q/openapi is served and fetched as JSON
PASS 13: the served document declares openapi 3.1.x
PASS 14: placeOrder has a stable operationId
PASS 15: getOrder has a stable operationId
PASS 16: OrderResponse.status declares exactly 5 enum values
PASS 17: OrderResponse.status enumerates the 5 OrderStatus values
PASS 18: OrderCreateRequest declares its 4 fields required
PASS 19: POST /orders declares an application/problem+json 400 response
PASS 20: GET /orders/{id} declares an application/problem+json 404 response
PASS 21: the served OpenAPI document matches the committed contract, normalized
21/21 checks passed
```

## Tests and checks in detail

`StrictSelfDescribingContractTest` (`mvn -B verify`) exercises `OrderResource`, `PlaceOrderRoute`
and `OrderService` against Dev Services Kafka and PostgreSQL: placing an order returns 201 and
publishes exactly one `OrderPlaced` record; getting that order back by id returns 200 with its
current state; an unknown id is rejected with 404 `application/problem+json`; an invalid order
(quantity 0) is rejected with 400 `application/problem+json`; and `/q/openapi` itself is fetched
and asserted to declare `openapi: 3.1.x`, both stable operationIds, the 5-value `OrderStatus`
enumeration, the 4 required `OrderCreateRequest` fields, and both problem+json error responses.

`verify.sh` adds the live, container-level view of the same contract, plus the normalized diff
against the committed `contracts/07-order-service.openapi.yaml`, which the unit test does not
check (there is nothing to diff against inside the Dev Services JVM).

## Experiments

Each row changes a single thing and names the checks that fail.

| Switch | Effect | Failing checks |
|---|---|---|
| Remove `@Schema(enumeration = ...)` from `OrderResponse.status` | The generated schema shows only `type: string`, no enum | 16, 17, 21 |
| Remove `@Schema(required = true)` from every `OrderCreateRequest` field | The generated schema carries no `required` array | 18, 21 |
| Remove `operationId` from both `@Operation` annotations | Both operations lose their stable id | 14, 15, 21 |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../OrderResource.java` | the agent-facing Quarkus REST surface: `placeOrder` and `getOrder`, fully annotated |
| `src/main/java/.../PlaceOrderRoute.java` | the Camel route: writes the order, then publishes `OrderPlaced` |
| `src/main/java/.../OrderService.java` | the `@Transactional` order write and read against `orders.orders` |
| `src/main/java/.../OrderCreateRequest.java`, `OrderResponse.java`, `OrderPlacedEvent.java`, `Problem.java` | per-example request, response, event and error shapes |
| `src/main/resources/db/migration/` | Flyway migration for `orders.orders` |
| `contracts/07-order-service.openapi.yaml` (repo root) | the committed, generated contract |
| `compose.yaml` | includes the shared infrastructure and adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
