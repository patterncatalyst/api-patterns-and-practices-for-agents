---
title: "Strict Self-Describing Contract (OpenAPI 3.1)"
order: 7
part: "Build: synchronous APIs"
description: "A precise OpenAPI 3.1 document, generated from the code and kept in sync with it, is the contract an agent actually reads before calling a tool."
duration: "25 minutes"
---

## Learning objectives

- Generate the OpenAPI 3.1 document from annotated code at build time instead of maintaining a second copy by hand.
- Mark every required field, every status field's enumerated values, and a stable `operationId` per operation.
- Commit the served document as the record of what was generated and diff a later run against it to catch drift.
- Recognize a schema that degrades to a bare `type: string` as the negative witness a careless or missing annotation leaves behind.

## Why this matters

An agent never reads the Java that implements an endpoint. It reads whatever
document describes the endpoint, a generated OpenAPI file or a tool
definition built from one, and calls the operation based on what that
document says the operation needs. A document a developer wrote by hand and
never revisited drifts from the code the moment a field is renamed or a
status value is added; a document generated from the code on every build
cannot drift, because there is no second copy left to fall out of sync.

## Pattern: Strict Self-Describing Contract (OpenAPI 3.1)

### The problem

A loose or hand-maintained OpenAPI document states what its author remembers
about the API, not what the running service actually accepts and returns. A
field that is actually required shows up as optional because nobody updated
the document when the validation was added; an enumerated status field shows
up as a bare string because the document was drafted before the enum existed.
An agent binding to that document has no way to tell the difference between a
field the service truly does not need and one the document simply forgot to
mark. It discovers the gap only by calling the operation and getting
rejected.

### The solution

**A precise OpenAPI 3.1 document, generated directly from annotated code at
build time and committed as the record of what was generated, is the
instruction set an agent actually binds to, so the document can never say
anything the code does not also enforce.**

### How Quarkus and Camel model it

`OrderResource` serves `placeOrder` and `getOrder` as plain Quarkus REST
resources; Camel sits on the event edge, publishing one `OrderPlaced` record
per new order, never terminating the HTTP request itself. The strictness
lives entirely in the annotations `quarkus-smallrye-openapi` reads to
generate the document:

```java
@POST
@Operation(operationId = "placeOrder", summary = "Place a new order.",
        description = "Creates an order and publishes OrderPlaced. Every field in the request body is required.")
@APIResponse(responseCode = "201", description = "The order was placed.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = OrderResponse.class)))
@APIResponse(responseCode = "400", description = "The request is invalid.",
        content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
public Response placeOrder(OrderCreateRequest request) { ... }
```

`operationId` gives a generated client or a tool registry a stable name to
bind to instead of guessing one from the path and the verb. The request and
response records carry the rest of the strictness: every field of
`OrderCreateRequest` is marked `required`, and `OrderResponse.status` names
its full value set, because Jackson serializes an enum as a plain string and
the schema would otherwise show only `type: string` with no indication of
what values are actually legal:

```java
public record OrderCreateRequest(
        @Schema(description = "The ordering customer's id.", required = true) String customerId,
        @Schema(description = "The ordered item's SKU.", required = true) String itemSku,
        @Schema(description = "How many units of the item.", required = true) int quantity,
        @Schema(description = "The order total, two decimals.", required = true) BigDecimal amount) {
}

public record OrderResponse(
        ...,
        @Schema(description = "The order's lifecycle state.",
                enumeration = {"PLACED", "PAID", "SHIPPED", "DELIVERED", "CANCELLED"}) String status) {
}
```

Both error responses are `application/problem+json` against a `Problem`
schema, not a bare string. `quarkus-smallrye-openapi` emits the whole
document at `/q/openapi` as `openapi: 3.1.x` without any version override;
the committed `contracts/07-order-service.openapi.yaml` is that same document
fetched from a live run and checked in verbatim, so a drift between what the
code generates today and what was committed is something a diff can catch
directly, rather than something a reader has to notice by eye.

{% include excalidraw.html file="07-self-describing-contract" alt="A hand-maintained contract loses its enum, required fields and operationId over time; a contract generated from annotated code on every build carries all three because there is nothing else to fall out of sync" caption="Figure 7.1: A generated contract cannot drift from the code, because it is the code" %}

Removing the annotations breaks the guarantee directly: stripping
`@Schema(enumeration = ...)` from `OrderResponse.status` leaves the generated
schema showing only `type: string`, stripping `@Schema(required = true)`
from `OrderCreateRequest`'s fields leaves the schema with no `required`
array at all, and stripping `operationId` from both `@Operation` annotations
leaves both operations unnamed in the document. The service still runs and
still answers every call correctly; only the contract an agent would read
before calling it loses the very information that made it strict.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must publish a contract precise enough that an agent can bind to an operation by a stable name and know which fields are required and which values a status field can take, without calling the operation first to find out.
> **Contract shape:** operation intent "describe every operation precisely enough to call it correctly on the first try"; inputs none beyond the service's own OpenAPI endpoint; response shape an OpenAPI 3.1 document with a stable operationId per operation, a required array per request schema, and an enum array per status field; error shape application/problem+json declared in the document itself, not left implicit; no idempotency key needed, this is a read of metadata, not a write.
> **Build it here:** "Using `OrderResource` and `OrderCreateRequest` as the model, add a third operation to this service with its own stable operationId, mark every one of its required fields, and confirm the generated document at `/q/openapi` carries all three."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): generate the API's own machine-readable contract from the code's type annotations, so every required field and every enumerated value is declared in the document rather than left to be discovered by trial and error."
> Check: removing a field's required annotation or a status field's enumerated values from the code removes that information from the generated document, never from the service's own behavior, confirming the document is read from the code rather than maintained beside it.

## Build, run, observe

```bash
./verify.sh
```

Run from the strict-self-describing-contract example, this brings up Kafka
and PostgreSQL alongside the service, places and reads back an order,
fetches the served `/q/openapi` document, and diffs it against the committed
contract after normalizing both to sorted-key JSON so formatting never
causes a false mismatch. It ends at 21/21.

Try:

- Remove `operationId` from both `@Operation` annotations and rerun: the
  diff against the committed contract fails, because the served document no
  longer names either operation.
- Remove `@Schema(enumeration = ...)` from `OrderResponse.status` and rerun:
  the generated schema shows only `type: string`, no enum.

## Anti-patterns

- **A hand-maintained contract.** A document stating what its author remembers about the API, not what the running service actually accepts and returns, drifts the moment a field is renamed or a status value is added.
- **An enum degraded to a bare string.** Omitting `required` or `enumeration` annotations leaves a schema showing only `type: string`, the pattern's own negative witness of what a careless annotation produces.
- **Treating "generated" as a synonym for "strict."** A document generated from careless annotations is still generated; generation removes drift, not the discipline of marking every field required and every status value enumerated.

## When not to use it

A document generated straight from annotations is only as strict as the
annotations a developer actually wrote; nothing forces every field to be
marked required or every status field to be enumerated, and a careless
annotation is just as silent a gap as a hand-written one that drifted. An
internal service with no external or agent caller gains little from this
rigor beyond what its own test suite already enforces, since there is no
reader outside the team who depends on the document being precise.

## References

- OpenAPI Specification, version 3.1
- JJ Geewax, *API Design Patterns*, Manning, 2021
- Leonard Richardson, Mike Amundsen, Sam Ruby, *RESTful Web APIs*, O'Reilly, 2013
- Quarkus OpenAPI and Swagger UI guide: [quarkus.io/guides/openapi-swaggerui](https://quarkus.io/guides/openapi-swaggerui)
- Apache Camel documentation: [camel.apache.org/docs](https://camel.apache.org/docs/)
