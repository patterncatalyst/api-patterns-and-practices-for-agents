---
title: "Search over Pagination"
order: 4
part: "Build: synchronous APIs"
description: "A typed-filter search call returns exactly the matching orders directly, measured against a raw pagination sweep that must scan page after page to find the same records."
duration: "20 minutes"
---

## Learning objectives

- Bind typed, optional filter fields server-side and evaluate the filter inside the backend.
- Return only the rows that match a filter, capped, in one call, regardless of total table size.
- Contrast one search call against a pagination sweep in round trips and bytes for the same table.
- Recognize an endpoint that ignores its own filter as the negative witness for what the pattern exists to prevent.

## Why this matters

Raw offset pagination answers one question: what is on this page. It never
answers the question an agent actually has, which is which rows match. A
caller who already knows the filter it wants, a status and an item SKU, gets
nothing useful from "page 0 of 10" except a chance to check each row by hand
and ask for the next page if none matched. A typed search endpoint resolves
the filter server-side and returns only the rows that satisfy it, in one
call, regardless of how many total rows exist.

## Pattern: Search over Pagination

### The problem

A caller who does not already know where a target row sits inside a raw,
offset-paginated list has no way to skip ahead to it. The only strategy is a
sweep: request page after page, inspect every row returned, and keep going
until either a match turns up or the server reports no pages remain. Every
page costs a full round trip and a full page of bytes, whether or not that
page held anything useful, and the sweep has to run to completion before a
caller can be sure a match does not exist at all.

### The solution

**A typed, filtered search endpoint evaluates the filter inside the backend
and returns only the matching rows directly, so a caller that already knows
what it wants never pages to find it.**

### How Quarkus models it

`GET /orders/search` binds typed, optional filter fields with `@BeanParam`
and `@RestQuery`, status as the `OrderStatus` enum, item SKU, customer id, and
an amount range, and builds the query from only the filters actually
supplied:

```java
@GET
@Path("/search")
public List<OrderSummary> search(@BeanParam OrderSearchFilter filter) {
    StringBuilder sql = new StringBuilder("SELECT ... FROM orders.orders WHERE 1 = 1");
    if (filter.status != null) sql.append(" AND status = ?");
    if (filter.itemSku != null) sql.append(" AND item_sku = ?");
    ...
    sql.append(" ORDER BY id LIMIT ").append(RESULT_CAP);
    return query(sql.toString(), params);
}
```

The result is still capped, 50 rows per call, so a search that is too broad
does not turn into an unbounded response; it simply stops being useful as a
precise search. `GET /orders?page=&size=` sits alongside it as the raw
pagination baseline over the exact same table, with no filters at all, and
over-fetches one extra row per page to learn whether another page follows
without a second round trip just to find out.

Against a 60-row deterministic seed, one call to `/orders/search` for a
specific status and item SKU returns exactly the 3 matching orders in 1 round
trip and 309 bytes. A pagination sweep over the same table, page size 10,
unable to know in advance which page holds a match, needs 6 round trips and
6479 bytes to see every row. The gap is not a rounding error: it is the cost
of making the caller do server-side work on the client side, one page at a
time.

{% include excalidraw.html file="04-search-over-pagination" alt="A raw pagination sweep pulling six pages to find three matching rows, versus one typed search call that isolates the same three rows directly" caption="Figure 4.1: Same 60-row table, two ways to find 3 rows" %}

Ignoring the filter and always running the unfiltered query breaks the
pattern's whole point without raising an error: the single call still
returns, still costs one round trip, and still looks cheap, but it returns
orders of every status and every SKU instead of the 3 expected matches. A
cheap call that returns the wrong rows is worse than a sweep that at
least returns the right ones eventually.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must let an agent request exactly the rows matching a filter in one call, never page through the table to find them.
> **Contract shape:** operation intent "search orders by filter"; inputs typed, optional filter fields (never a page number or offset); response shape a capped list of matching rows; error shape an empty list, not an error, for no matches; no idempotency key, the operation only reads.
> **Build it here:** "Using `OrderSearchResource` and `OrderSearchFilter` as the model, add a `minQuantity`/`maxQuantity` filter pair to the search endpoint, bound the same way as the existing amount range, and keep the result capped at `RESULT_CAP`."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): a typed filter endpoint or field that returns only the matching orders directly, never a raw offset-and-limit page a caller has to sweep."
> Check: a single call to the filtered endpoint returns exactly the expected matching set and nothing else, no matter how many total rows the table holds.

## Build, run, observe

```bash
./verify.sh
```

Run from the search-over-pagination example, this seeds 60 deterministic
rows, confirms one filtered search call isolates exactly the expected
matches, and measures the live round-trip and byte contrast between that
single call and a full pagination sweep over the same table. It ends at
11/11.

Try:

- Compare the printed call counts and byte totals for the search call against
  the pagination sweep on your own run.
- Hardcode the search endpoint to ignore its filter and rerun: it still
  returns in one call, but no longer the right rows.

## Anti-patterns

- **Raw offset pagination as the only way to find a row.** A caller who does not already know where a target sits has no way to skip ahead to it, only a page-by-page sweep.
- **Ignoring the filter and running the unfiltered query.** The pattern's own negative witness: the call still looks cheap, one round trip, but returns every status and every SKU instead of the expected matches.
- **An uncapped search returning an unbounded response.** A filter too broad to narrow anything should still stop at a result cap, not turn into a full-table dump.

## When not to use it

A workload that always needs every row, a full export or a batch
reconciliation job, is not better served by a search filter: there is no
filter narrow enough to replace "give me everything," and an endpoint
designed for that case should say so explicitly rather than being approached
through search with an empty filter.

## References

- JJ Geewax, *API Design Patterns*, Manning, 2021
- Leonard Richardson, Mike Amundsen, Sam Ruby, *RESTful Web APIs*, O'Reilly, 2013
- Anthropic, *Effective context engineering for AI agents*: [anthropic.com/engineering/effective-context-engineering-for-ai-agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- Quarkus REST guide: [quarkus.io/guides/rest](https://quarkus.io/guides/rest)
