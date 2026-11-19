# 29 Scripted non-interactive consumption

A scripts-only harness that drives the same order-service tool contract a human would exercise by
hand, but scripted and repeatable: no browser, no interactive session, no human in the loop. It
reuses ch07's strict-self-describing-contract order service unchanged, exactly as the Part 4
examples reuse their targets: this example never copies or forks ch07's code, it builds ch07's
image through compose from ch07's own Containerfile and build context.

The runner is Newman, the Postman collection runner, the house REST-test tool. It runs as a pinned
one-shot container on the apa network and reaches the service by compose service DNS
(`strict-self-describing-contract:8080`), never through the host port. The committed
`order-service.postman_collection.json` is the reader artifact: a real Postman v2.1 collection that
a reader can open in Postman or run with any Newman, against the published host port, to see the
same drive.

## What the collection drives

The collection walks the four moves a consumer makes against ch07's two operations, asserting the
status code, the content type and the body shape on each:

| Request | Call | Asserts |
|---------|------|---------|
| place order returns 201 | `POST /orders` with a valid body | 201, `application/json`, a non-empty `orderId`, the echoed `customerId`, `status` is `PLACED`; saves `orderId` for the next request |
| read order back returns 200 | `GET /orders/{{orderId}}` | 200, `application/json`, the same `orderId` back, `status` is `PLACED` |
| unknown order returns 404 problem+json | `GET /orders/O-NOPE` | 404, `application/problem+json`, a `Problem` body with `title`, `status` 404 and `detail` |
| invalid create returns 400 problem+json | `POST /orders` with `quantity` 0 | 400, `application/problem+json`, a `Problem` body with `title`, `status` 400 and `detail` |

`baseUrl` is a collection variable (default `http://localhost:8080`, the published host port).
`verify.sh` overrides it with `--env-var baseUrl=http://strict-self-describing-contract:8080` so the
containerized run targets the service by DNS on the apa network.

## Asserting the effect, not the exit code

`verify.sh` never reads Newman's exit code. Newman writes a JSON report to a host-mounted directory
(`-v "$REPORT_DIR:/etc/newman/out"`, `--reporter-json-export /etc/newman/out/report.json`), run as
the host user so the file is host-owned; the report is transient and removed on exit, never
committed. The checks read that report with `jq`:

- the report was written and parses,
- all four requests ran,
- `run.stats.assertions.failed` is 0 with a positive `run.stats.assertions.total`,
- the unknown-order request ran its assertions and every one passed.

## The biting negative witness

The "unknown order returns 404 problem+json" assertion is this harness's own negative witness. When
ch07 drifts, its exception handling removed so `GET /orders/O-NOPE` falls through to a 500 with a
`text/plain` body, the status and content-type assertions on that request gain an `error` in the
JSON report. `verify.sh` reads the report for exactly that request by name and fails its check when
any of its assertions carries an `error`, so the drift is observable from the report alone, without
reading Newman's exit code. The live bite rebuilds ch07's image with its error mappers removed and
reruns this harness; the check turns from PASS to FAIL and the failed-assertion count rises above
0.

## Run

```bash
./verify.sh
```

A fresh stack comes up (ch07's order service plus the shared infrastructure and LGTM), Newman drives
the collection from a one-shot container on the apa network, the checks read the JSON report, and
the stack is torn down. Set `KEEP=1` to leave the stack running, then run the committed collection
by hand against the published host port with any local Newman or from Postman.

## Layout

| Path | Purpose |
|------|---------|
| `order-service.postman_collection.json` | the committed Postman v2.1 collection: the four requests and their assertions |
| `compose.yaml` | includes the shared infrastructure and builds ch07's order service as its own service |
| `verify.sh` | runs Newman as a pinned one-shot container on the apa network and asserts on its JSON report |
