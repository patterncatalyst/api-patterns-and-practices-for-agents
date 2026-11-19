---
title: "Token-Efficient Response / Agent BFF"
order: 9
part: "Build: synchronous APIs"
description: "A backend-for-frontend layer shapes a response for the token budget an agent actually has, instead of returning a human-facing payload unchanged."
duration: "25 minutes"
---

## Learning objectives

- Build a purpose-built projection read by its own narrow query, never the full entity reshaped after loading.
- Return human-readable ids and drop internal fields a task never uses.
- Narrow the response further with GraphQL field selection on top of an already-lean projection.
- Measure the byte contrast between a full payload, a projection and a field-selected query to confirm the saving is real.

## Why this matters

Every field in a response an agent reads has to sit in the same bounded
context window as the task's instructions, the tool definitions, and
whatever the agent has already seen. A payload built for a human dashboard
carries internal identifiers, audit history and free-text notes nobody asked
for, and an agent paying for every one of those fields in tokens gets no
benefit from the ones it never needed in the first place.

## Pattern: Token-Efficient Response (Agent BFF)

### The problem

The full human payload for an order carries an internal surrogate key, a
nested customer record with its own internal id, free-text internal notes,
and a complete audit trail, fields a human-facing dashboard renders or a
compliance screen inspects. None of that is useful to an agent task that
only needs to know what was ordered, for how much, and in what state.
Returning that same payload to an agent under a friendlier name, a "summary"
endpoint that just forwards the full entity, pays the full token cost
without removing any of the actual weight.

### The solution

**A purpose-built projection returns exactly the fields a task needs, read
by its own narrow query rather than the full entity's result reshaped after
the fact, so the token cost of a response matches what the task actually
uses.**

### How Quarkus models it

`OrderResource` serves two reads over the same order: the full human payload
and a lean, agent-facing summary.

```java
@GET @Path("/{id}")
public OrderFull full(@PathParam("id") String id) { return reads.full(id); }

@GET @Path("/{id}/summary")
public OrderSummary summary(@PathParam("id") String id) { return reads.summary(id); }
```

`OrderReadService` is the one CDI bean both reads, and the GraphQL surface
below, share, and the projection is not the full entity trimmed in Java
after loading it: `summary` runs its own `SELECT` against only the lean
columns, a separate purpose-built read rather than `full`'s result reshaped:

```java
private static final String FULL_COLUMNS = "id, record_uuid, customer_uuid, customer_id, "
        + "customer_full_name, customer_email, customer_risk_score, item_sku, quantity, amount, "
        + "status, internal_notes, audit_trail";
private static final String SUMMARY_COLUMNS = "id, customer_id, item_sku, quantity, amount, status";
```

`OrderSummary` carries exactly six fields, no `recordUuid`, no nested
customer record, no `internalNotes`, no `auditTrail`, and its order id is
always the human-readable one, never the raw `recordUuid` or
`customerUuid`. The read-only GraphQL surface, `OrderGraphQLApi`, exposes
the same projection through `@Query` methods only, no mutations in the
generated schema, and GraphQL's own field selection lets a caller narrow the
response even further than `OrderSummary` already does: a query selecting
only `orderId` and `amount` returns exactly those two fields and nothing
else.

{% include excalidraw.html file="09-agent-bff" alt="The full payload wrapped under a friendlier name still carries every heavy and internal field; a purpose-built projection, read by its own narrow query, carries only what the task needs, and GraphQL field selection narrows it further still" caption="Figure 9.1: Reshape the read, do not just rename the response" %}

Measured on the same seeded order, the full REST payload is 571 bytes, the
REST summary projection is 110 bytes, and a GraphQL query selecting exactly
two fields is 62 bytes. Changing the summary endpoint to call `reads.full`
instead of `reads.summary`, wrapping the full entity under the summary path
rather than reshaping it, erases the saving entirely: the projection then
costs the same 571 bytes as the full payload, with `recordUuid`, the nested
customer record, `internalNotes` and `auditTrail` all still present.
Hand-rolling the GraphQL query's return type as a raw string instead of
letting the framework project the typed `OrderSummary` breaks the other
half of the guarantee: a client can no longer select a subset of fields from
a flattened string, so the two-field query that used to return exactly
`orderId` and `amount` returns nothing at all.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must let an agent read exactly the fields a task needs, never the full human-facing payload under a different name, so the token cost of a response tracks what the task actually uses.
> **Contract shape:** operation intent "read a lean, task-shaped projection of an order, by id"; inputs an order id; response shape the minimal field set a typical agent task needs, with a human-readable id and no internal or raw identifier fields; error shape a 404 for an unknown id; no idempotency key needed, this is a read.
> **Build it here:** "Using `OrderReadService` and `OrderSummary` as the model, add a second lean projection for a different subset of fields, backed by its own narrow query rather than `full`'s result trimmed afterward, and confirm its byte size against the full payload."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): a purpose-built read that selects only the fields a task needs directly at the data layer, with GraphQL field selection narrowing the response further still, instead of returning the full entity under a thinner name."
> Check: a projection endpoint's response is backed by its own narrow query, never by loading the full entity and discarding fields afterward, so wrapping the full entity under the projection's path always costs the same bytes as the full payload, never less.

## Build, run, observe

```bash
./verify.sh
```

Run from the token-efficient-response-bff example, this brings up
PostgreSQL alongside the service, reads the full payload and the summary
projection for the same seeded order, runs a two-field GraphQL query, and
measures the byte size of all three responses. It ends at 15/15.

Try:

- Change `OrderResource.summary` to call `reads.full(id)` and rerun: the
  summary response grows back to the full payload's byte size.
- Change the GraphQL `orderSummary` return type to a hand-rolled string and
  rerun: the two-field selection query stops returning any data at all.

## Anti-patterns

- **Forwarding the full entity under a friendlier name.** A "summary" endpoint that just forwards the full payload pays the full token cost without removing any of the actual weight, the pattern's own negative witness.
- **Trimming fields in Java after loading the full entity.** Reshaping the full entity's result in application code still runs the heavy query underneath; the saving only exists when the projection has its own narrow query.
- **Hand-rolling the GraphQL return as a raw string.** Flattening a typed projection into a string response removes field selection entirely, so a caller can no longer narrow a query to the two fields it actually wants.

## When not to use it

A projection read by its own narrow query is an extra query to write and
maintain alongside the full one, and a service with only one or two
consumers that always need the full payload gains nothing from splitting a
read it never needs to shrink. A projection also has to be kept accurate as
the full entity's shape changes: a field removed from the full payload but
left stale in the projection's own query becomes its own small drift to
track, the same discipline problem this pattern otherwise removes from the
response shape.

## References

- JJ Geewax, *API Design Patterns*, Manning, 2021
- GraphQL Specification, field-selection semantics: [spec.graphql.org](https://spec.graphql.org/)
- Anthropic, *Effective context engineering for AI agents*: [anthropic.com/engineering/effective-context-engineering-for-ai-agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- Quarkus SmallRye GraphQL guide: [quarkus.io/guides/smallrye-graphql](https://quarkus.io/guides/smallrye-graphql)
