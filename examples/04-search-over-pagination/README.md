# 04 Search over pagination

An order-service search that proves chapter 04's thesis with a measurement, not an assertion:
one call to a typed search endpoint isolates its target, where raw offset pagination forces a
caller that does not already know where the target sits to sweep every page. No Camel is
needed here: this is pure Quarkus REST (JAX-RS) and JDBC.

`GET /orders/search` takes typed, optional filters bound with `@BeanParam` and `@RestQuery`
(`status` as the `OrderStatus` enum, `itemSku`, `customerId`, `minAmount`, `maxAmount`) and
returns only the matching orders, capped at `OrderSearchResource.RESULT_CAP` (50) rows per
call. `GET /orders?page=&size=` is the comparison surface: raw offset pagination over the same
`orders.orders` table, with no filters at all, the shape a caller is stuck with when searching
means "page through everything and check each row yourself."

## The measured contrast

The table is seeded deterministically (`V2__seed.sql`, 60 rows, `n` in 1 to 60): status, item
SKU and customer cycle on different moduli, so the expected result of any filter combination
is computable, not guessed. For `status=PLACED&itemSku=SKU-2`, exactly 3 orders match
(`O-0005`, `O-0025`, `O-0045`), spread across the full id range, not clustered on one page.

`verify.sh` measures, on a live run, the actual call count and bytes transferred for both
approaches: one call to `/orders/search` versus a pagination sweep (page size 10) that keeps
calling until the server reports no more pages, since a caller with only raw pagination cannot
know in advance which page (if any) holds a match. See `_plans/claims.md` for the recorded
numbers from the 2026-11-18 run.

## The negative witness

The witness is "pagination in context": a single search call must isolate exactly the target
rows, excluding every non-matching row, which a pagination sweep cannot do without scanning
everything. To confirm the "one call isolates the target" check actually discriminates, the
filter was temporarily ignored in `OrderSearchResource.search` (hardcoded to always run the
unfiltered query, as if returning page one regardless of the caller's filters). With the
filter ignored, the single call returned orders of every status and every SKU instead of the
3 expected matches. `mvn -B verify` then failed `searchReturnsExactlyTheMatchingOrdersAndNoOthers`,
and a live `./verify.sh` failed the "one search call returns exactly the 3 matching orders"
check. Both were reverted and the suite reverified green. See `_plans/verification-log.md` for
the exact run.

## Run

```bash
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin and `jq`; it builds the service and
the shared domain model inside the image, starts PostgreSQL and LGTM plus the service, runs
the checks and the measurement below, and removes everything with `down -v`. Set `KEEP=1` to
leave the stack running for inspection.

Run the tests alone (Quarkus Dev Services starts a PostgreSQL container; no compose stack
needed). Install the shared domain model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Expected output

```
PASS 1: apa-kafka is healthy
...
PASS 7: apa-search-over-pagination is healthy
PASS 8: one search call returns exactly the 3 matching orders and excludes every other
PASS 9: an unfiltered search is still bounded by the result cap
--- measured call-count contrast (search vs. raw pagination) ---
search:  calls=1 bytes=... matches=3
paging:  calls=6 bytes=... (60 rows, page size 10, matches scattered across every page)
PASS 10: the paging sweep needs more calls than the one search call
PASS 11: the paging sweep transfers more bytes than the one search call
11/11 checks passed
```

## Tests and checks in detail

`OrderSearchTest` (`mvn -B verify`) exercises the REST resource directly against Dev Services
PostgreSQL: a filtered search returns exactly the 3 seeded `PLACED`/`SKU-2` orders and nothing
else, an unfiltered search stays within the result cap, and raw pagination reports `hasMore`
correctly across the full 60-row, 6-page sweep.

`verify.sh` adds the live, container-level view and the measurement: the same filtered search
against the running stack, and an actual round trip of the pagination sweep, with the call
count and byte totals printed, not asserted in prose.

## Experiments

Each row changes a single thing and names the checks that fail.

| Switch | Effect | Failing checks |
|---|---|---|
| Ignore the filter in `OrderSearchResource.search` (always run the unfiltered query) | The single search call no longer isolates the target; it returns orders of every status and SKU | 8 |
| Lower `RESULT_CAP` below the seeded row count and request an unfiltered search | The cap check still passes (the cap is the point), but a caller relying on "one call sees everything" would be wrong | none (demonstrates the cap exists; not a chapter check) |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../OrderSearchResource.java` | `GET /orders/search` (typed filters) and `GET /orders` (raw pagination), over the same table |
| `src/main/java/.../OrderSearchFilter.java` | the `@BeanParam` filter bean, bound field by field with `@RestQuery` |
| `src/main/java/.../OrderSummary.java`, `OrderPage.java` | the search hit and the paging envelope (per-example, not in domain-model) |
| `src/main/resources/db/migration/` | Flyway migration and the 60-row deterministic seed |
| `compose.yaml` | includes the shared infrastructure and adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks and the measurement above |
