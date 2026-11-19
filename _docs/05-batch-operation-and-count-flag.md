---
title: "Batch Operation and Count Flag"
order: 5
part: "Build: synchronous APIs"
description: "An id-keyed batch endpoint resolves many identifiers in one call with explicit misses, and a count endpoint answers how many with a bare number, each measured against the one-at-a-time and fetch-everything alternatives they replace."
duration: "20 minutes"
---

## Learning objectives

- Resolve many known ids in one call, with hits keyed by id and misses named explicitly.
- Answer "how many" with a bare number instead of the records a caller would otherwise fetch to count.
- Measure a batch call and a count call against their one-at-a-time and fetch-everything baselines.
- Recognize a count endpoint returning full records as the negative witness for the shape it exists to avoid.

## Why this matters

An agent that already knows which ten order ids it needs should not have to
spend ten round trips finding out which of them exist. An agent that only
needs to know how many orders are PLACED should not have to download every
PLACED order's full record just to count the rows itself. Both shapes answer
a narrower question than "give me everything," and a narrower question
deserves a response sized to match it.

## Pattern: Batch Operation and Count Flag

### The problem

Without a batch endpoint, resolving several known ids costs one call per id,
each paying the full overhead of a round trip for a single row. Without a
count endpoint, answering "how many" costs the same bytes as answering "which
ones," because the only way to get a number is to fetch every matching
record and count what came back. Both gaps charge a caller for information
it never asked for, size of a full record when the actual question was
narrower.

### The solution

**A batch endpoint keyed by id resolves many identifiers in one call with
explicit misses named, and a count endpoint returns a bare number instead of
the records a caller would otherwise fetch to compute it.**

### How Quarkus models it

`POST /orders/batch-get` takes a JSON array of ids and returns the hits keyed
by id alongside an explicit `notFound` list, so an agent gets both the
answers it found and the ones it did not, in one response:

```java
@POST
@Path("/batch-get")
public BatchGetResponse batchGet(List<String> ids) {
    Map<String, OrderSummary> hits = ...;        // WHERE id = ANY(?)
    List<String> notFound = ids.stream().filter(id -> !hits.containsKey(id)).toList();
    return new BatchGetResponse(hits, notFound);
}
```

`GET /orders/count` answers a narrower question with a narrower response: a
bare number, never a record:

```java
@GET
@Path("/count")
public Long count(@RestQuery OrderStatus status) {
    String sql = status == null ? "SELECT COUNT(*) FROM orders.orders"
                                 : "SELECT COUNT(*) FROM orders.orders WHERE status = ?";
    ...
    return rs.getLong(1);
}
```

`GET /orders/{id}` and `GET /orders` (every row, no filter) are the
comparison baselines: the shape a caller is stuck with when batch-get or the
count flag do not exist. A GraphQL field reaches the same shape a different
way: a query that selects only a `count` field, never the full record set,
lets the resolver skip materializing rows it was never asked to return, the
same saving a REST count endpoint gets from skipping the record body
entirely.

Against a 36-row deterministic seed, one `/orders/batch-get` call for 10 ids
(8 hits, 2 misses) costs 1 round trip and 933 bytes, where the same 10 ids
fetched one at a time cost 10 round trips and 890 bytes total: more calls,
each one small, adding up to roughly the same data for a worse shape to work
with. The count contrast is sharper: one `/orders/count` call costs 2 bytes,
where fetching every row to count them client-side costs 3718 bytes for the
same 36-row table.

{% include excalidraw.html file="05-batch-and-count" alt="Ten single-id GETs and a fetch-all count on one side, one batch-get call and one count-flag call on the other, with the measured call counts and byte totals" caption="Figure 5.1: Two shapes, two measured contrasts, same table" %}

Changing the count endpoint to return the full record list instead of the row
count breaks the guarantee in the most visible way possible: the response
stops being a number and becomes a JSON array, the exact shape the count flag
exists to avoid returning.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must let an agent resolve many known ids in one call and answer how many without paying for which ones.
> **Contract shape:** operation intent "resolve ids in bulk" and "count by filter"; inputs an id array, or a filter; response shape hits keyed by id plus an explicit miss list, or a bare number; error shape an empty hits map and a full miss list, not an error, when nothing matches; no idempotency key, both operations only read.
> **Build it here:** "Using `OrderBatchResource` and `BatchGetResponse` as the model, add a `POST /orders/batch-status` endpoint that accepts an id array and returns only each id's status, keyed by id, with unknown ids named in the same `notFound` shape as `batchGet`."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): a batch endpoint or field that resolves many ids in one call with misses named explicitly, and a count field that returns only a number by selecting it instead of the full record set."
> Check: a batch call for a known id set returns every hit and names every miss in one response, and a count call's body is numeric only, never a record.

## Build, run, observe

```bash
./verify.sh
```

Run from the batch-and-count example, this seeds 36 deterministic rows,
confirms one batch-get call isolates hits from misses and one count call
returns a bare number, and measures the live round-trip and byte contrast for
both pairs. It ends at 12/12.

Try:

- Compare the printed byte totals for the count call against the fetch-all
  baseline on your own run.
- Change the count endpoint to return the full record list and rerun: the
  body becomes an array instead of a number.

## Anti-patterns

- **One call per id.** Resolving several known ids this way pays the full round-trip overhead of a single row for every id, when the caller already knows what it is asking for.
- **Returning full records to answer "how many."** The pattern's own negative witness: the response stops being a number and becomes a JSON array, the exact shape a count flag exists to avoid.
- **An unbounded id array on a batch endpoint.** A cap discipline is still needed; a single call that accepts any number of ids trades the pagination problem for an equally unbounded call.

## When not to use it

A count flag is wasted effort when every caller that would ask "how many"
also always needs the records themselves; returning a count and then
fetching the records anyway is two calls where one would have done. A batch
endpoint built for a small, bounded id set does not remove the need for a cap
discipline of its own: an unbounded array of ids in one request trades the
pagination problem for an equally unbounded single call.

## References

- JJ Geewax, *API Design Patterns*, Manning, 2021
- GraphQL Specification: [spec.graphql.org](https://spec.graphql.org/)
- Strawberry GraphQL documentation: [strawberry.rocks/docs](https://strawberry.rocks/docs)
- Quarkus REST guide: [quarkus.io/guides/rest](https://quarkus.io/guides/rest)
