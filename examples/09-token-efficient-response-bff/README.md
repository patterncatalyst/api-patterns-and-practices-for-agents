# 09 Token-efficient response and agent BFF

An order-service read side that proves chapter 09's thesis: an agent-facing response is a
purpose-built projection, not the full human payload wrapped under a friendlier name. Every
seeded order carries lean fields (a human-readable order id, item, quantity, amount, status)
alongside heavy and internal ones (an internal `recordUuid`, a raw `CustomerRecord` with its own
`customerUuid` and PII, free-text `internalNotes`, and a full `auditTrail`). Three surfaces read
the same table: `GET /orders/{id}` (the full human payload), `GET /orders/{id}/summary` (the
lean `OrderSummary` BFF projection), and a read-only GraphQL `orderSummary`/`orderSummaries`
query. The REST BFF and the GraphQL resolver both call the same `OrderReadService.summary`,
which runs its own narrow SQL query: the lean projection is a separate purpose-built read, not
`full`'s result reshaped in Java after the fact.

## The BFF projection and the GraphQL surface

`OrderSummary` carries exactly six fields: `orderId` (human-readable, `O-1001`), `customerId`,
`itemSku`, `quantity`, `amount`, `status`. It omits `recordUuid`, the nested `customer` record,
`internalNotes` and `auditTrail` entirely, and its `orderId` is never the raw `customerUuid` or
`recordUuid`. `OrderGraphQLApi` is a `@GraphQLApi` CDI bean with two `@Query` methods only
(`orderSummary`, `orderSummaries`); there is no mutation type in the generated schema. GraphQL's
own field-selection mechanics give a caller the ability to ask for fewer fields than
`OrderSummary` even carries: selecting only `orderId` and `amount` returns exactly those two.

## The negative witness

Two bites, each confirmed live and reverted.

**Wrap, not reshape (REST BFF).** `OrderResource.summary` was changed to return `OrderFull` by
calling `reads.full(id)` instead of `reads.summary(id)`. `mvn -B verify` then failed
`bffProjectionOmitsTheHeavyAndInternalFieldsAndCarriesNoRawUuid` (the response carried
`recordUuid`, `customer`, `internalNotes` and `auditTrail`) and
`bffProjectionIsMaterallySmallerThanTheFullPayload` (571 bytes either way). A live `./verify.sh`
run fell to 12/15, failing the omission check, the no-raw-UUID check and the byte-budget check
(`full REST payload: bytes=571`, `REST BFF projection: bytes=571`). The edit was reverted and
both reverified green.

**GraphQL over-return.** `OrderGraphQLApi.orderSummary`'s return type was changed from
`OrderSummary` to `String`, hand-rolling the full entity as a JSON string
(`objectMapper.writeValueAsString(reads.full(orderId))`) instead of letting the framework project
the typed object. A client's field selection cannot narrow a leaf scalar, so the committed query
(`{ orderSummary(orderId: "O-1001") { orderId amount } }`) is no longer valid against the new
schema. `mvn -B verify` then errored on `graphQlQuerySelectingTwoFieldsReturnsExactlyThoseTwo`
(a `NullPointerException`: `data.orderSummary` came back `null`). A live `./verify.sh` run fell
to 13/15, failing the exactly-two-fields check and the schema-drift check against the committed
`contracts/09-order-bff.graphql`. The edit was reverted and both reverified green, reproducing
the same measured numbers.

## Run

```bash
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin, `jq` and `python3`; it builds the
service and the shared domain model inside the image, starts Kafka, PostgreSQL, Apicurio,
Keycloak, Ollama and LGTM plus the service, runs the checks below and removes everything with
`down -v`. Set `KEEP=1` to leave the stack running for inspection.

Run the tests alone (Quarkus Dev Services starts PostgreSQL). Install the shared domain model
first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Expected output

```
PASS 1: apa-kafka is healthy
...
PASS 7: apa-token-efficient-response-bff is healthy
PASS 8: the full payload carries the heavy and internal fields
PASS 9: the BFF projection omits internalNotes, auditTrail, customer and recordUuid
PASS 10: the BFF projection carries the human-readable order id and no raw UUID anywhere
PASS 11: a GraphQL query selecting exactly orderId and amount returns exactly those two fields
PASS 12: the GraphQL schema is served at /graphql/schema.graphql
PASS 13: the served GraphQL schema matches the committed contract
--- measured byte contrast (full payload vs. BFF projections) ---
full REST payload:    bytes=571
REST BFF projection:  bytes=110
GraphQL (2 fields):   bytes=62
PASS 14: the REST BFF projection is materially fewer bytes than the full payload
PASS 15: the GraphQL response is fewer bytes than the full REST payload
15/15 checks passed
```

## Tests and checks in detail

`TokenEfficientResponseBffTest` (`mvn -B verify`) exercises `OrderResource`,
`OrderGraphQLApi` and `OrderReadService` against Dev Services PostgreSQL seeded with two
deterministic orders: the full payload carries the heavy and internal fields; the BFF projection
carries exactly the six lean field names and no raw UUID anywhere in its body; the BFF response
is smaller than the full response; a GraphQL query selecting `orderId` and `amount` returns
exactly those two fields; and `orderSummaries` returns the lean projection for every requested id.

`verify.sh` adds the live, container-level view of the same contract, the GraphQL schema fetch
and its diff against the committed `contracts/09-order-bff.graphql`, and the measured byte
contrast across all three surfaces, which the unit test does not measure in bytes.

## Experiments

Each row changes a single thing and names the checks that fail.

| Switch | Effect | Failing checks |
|---|---|---|
| `OrderResource.summary` calls `reads.full(id)` instead of `reads.summary(id)` | The BFF endpoint wraps the full entity instead of reshaping it | 9, 10, 14 |
| `OrderGraphQLApi.orderSummary` returns a hand-rolled JSON string instead of the typed `OrderSummary` | GraphQL field selection can no longer narrow the response | 11, 13 |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../OrderResource.java` | the two REST surfaces: full payload and BFF summary |
| `src/main/java/.../OrderGraphQLApi.java` | the read-only GraphQL surface: `orderSummary`, `orderSummaries` |
| `src/main/java/.../OrderReadService.java` | the one CDI read service both surfaces share |
| `src/main/java/.../OrderFull.java`, `OrderSummary.java`, `CustomerRecord.java`, `AuditEntry.java` | per-example response shapes |
| `src/main/resources/db/migration/` | Flyway migration and deterministic seed for `orders.orders` |
| `contracts/09-order-bff.graphql` (repo root) | the committed, generated GraphQL schema |
| `compose.yaml` | includes the shared infrastructure and adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
