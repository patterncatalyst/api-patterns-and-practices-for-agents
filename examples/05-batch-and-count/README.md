# 05 Batch operation and count flag

An order-service that proves chapter 05's thesis with two measurements: one batch-get call for
several ids beats one single-get call per id, and a count flag that returns a bare number beats
fetching every row just to count them. No Camel is needed here: this is pure Quarkus REST
(JAX-RS) and JDBC.

`POST /orders/batch-get` takes a JSON array of order ids and returns the hits keyed by id plus
an explicit `notFound` list of the ids that do not exist, in one call. `GET
/orders/count?status=...` returns only a number: no record fields ever appear in the response.
`GET /orders/{id}` (one id, one call) and `GET /orders` (every row, no filter) are the
comparison baselines: the shape a caller is stuck with when batch-get or the count flag do not
exist.

## The measured contrasts

The table is seeded deterministically (`V2__seed.sql`, 36 rows, `n` in 1 to 36): status cycles
every 4 rows, so `status=PLACED` is always exactly 9 rows.

`verify.sh` measures, on a live run, the call count and bytes transferred for both pairs: one
`/orders/batch-get` call for 10 ids (8 hits, 2 misses) versus 10 separate `/orders/{id}` calls;
and one `/orders/count` call versus one `/orders` call that returns all 36 full records for a
caller to count itself. See `_plans/claims.md` for the recorded numbers from the 2026-11-18 run.

## The negative witness

The witness is "a count response carries only a number, no record bodies": `verify.sh` checks
that `/orders/count`'s raw body matches `^[0-9]+$` and contains no `{` or `[`. To confirm that
check actually discriminates, `OrderBatchResource.count` was temporarily changed to return
`all()` (the full record list) instead of the row count. With the guard gone, the endpoint
returned a JSON array of 36 full order records instead of the bare number. `mvn -B verify` then
failed `countReturnsOnlyANumberNoRecordBodies`, and a live `./verify.sh` failed the "count flag
returns only the bare number" check. Both were reverted and the suite reverified green. See
`_plans/verification-log.md` for the exact run.

## Run

```bash
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin and `jq`; it builds the service and the
shared domain model inside the image, starts PostgreSQL and LGTM plus the service, runs the
checks and the measurements below, and removes everything with `down -v`. Set `KEEP=1` to leave
the stack running for inspection.

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
PASS 7: apa-batch-and-count is healthy
PASS 8: one batch-get call isolates hits from the unknown id's notFound entry
PASS 9: GET /orders/count?status=PLACED returns only the bare number 9, no record fields
PASS 10: an unfiltered GET /orders/count covers every seeded row
--- measured call-count contrast (batch-get vs single-get) ---
batch-get: calls=1 bytes=... ids=10 (8 hits, 2 misses)
single-get: calls=10 bytes=... (same 10 ids, one call each)
PASS 11: batch-get needs fewer calls than one single-get per id
--- measured byte contrast (count flag vs fetch-all-then-count) ---
count:     calls=1 bytes=...
fetch-all: calls=1 bytes=... (36 full records, counted client-side)
PASS 12: the count flag transfers far fewer bytes than fetching every row to count them
12/12 checks passed
```

## Tests and checks in detail

`OrderBatchTest` (`mvn -B verify`) exercises `OrderBatchResource` directly against Dev Services
PostgreSQL: a batch-get of a mix of known and unknown ids returns exactly the known hits keyed
by id and the unknown id in `notFound`; a filtered count returns the bare string `"9"`, not a
JSON object or array; an unfiltered count returns `"36"`; the single-get baseline returns 404
for an unknown id and 200 with the record for a known one.

`verify.sh` adds the live, container-level view and both measurements: the same batch-get and
count checks against the running stack, and the actual round-trip call counts and byte totals
for both contrasts, printed, not asserted in prose.

## Experiments

Each row changes a single thing and names the checks that fail.

| Switch | Effect | Failing checks |
|---|---|---|
| Change `OrderBatchResource.count` to return `all()` instead of the row count | The response becomes a 36-record JSON array instead of a bare number | 9 |
| Change `OrderBatchResource.batchGet` to ignore the requested ids and return every row as a hit | `notFound` is always empty and ids not in the request appear as hits | none of the committed checks (demonstrates scope creep the committed assertion does not cover; not this chapter's check) |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../OrderBatchResource.java` | `POST /orders/batch-get`, `GET /orders/count`, `GET /orders/{id}`, `GET /orders` |
| `src/main/java/.../OrderSummary.java`, `BatchGetResponse.java`, `Problem.java` | per-example response shapes |
| `src/main/resources/db/migration/` | Flyway migration and the 36-row deterministic seed |
| `compose.yaml` | includes the shared infrastructure and adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks and the measurements above |
