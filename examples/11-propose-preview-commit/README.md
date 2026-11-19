# 11 Propose, preview, commit

An order-service that proves chapter 11's thesis: an agent that is about to take an
irreversible action (cancel an order and refund its payment) should be able to ask what would
happen before it happens, on the same intent endpoint it would use to actually do it. There is
no separate `/preview` endpoint. `POST /orders/{id}/cancel` (body `{"reason": "...", "dryRun":
true|false, "decision": "REJECTED_BY_USER"}`) carries three outcomes:

- `dryRun: true` returns a structured `ReviewPayload` (before/after status, refund amount, the
  events committing would emit, warnings, whether the effect is reversible) and writes and
  publishes nothing.
- `dryRun` absent or `false` commits: the same ch03 cancel-and-refund saga runs, the order moves
  to `CANCELLED`, the payment moves to `REFUNDED`, and exactly one `OrderCancelled` event
  publishes to `apa.orders.cancelled`.
- `decision: "REJECTED_BY_USER"` is a graceful 200 no-op: the caller reviewed a preview and
  chose not to proceed. This is a normal terminal outcome, not an error.

The preview path (`CancelReviewService`) and the commit path (`CancelRefundSaga` plus
`CancelRefundRoute`, the Camel event edge) share the same existence and lifecycle guard, so a
preview on an order that cannot legally be cancelled reports the same 404 or 409 a commit
attempt would. Only the commit path ever opens a database transaction that writes, and only the
commit path ever reaches the `direct:cancel-and-refund` Camel route that publishes to Kafka.

## The negative witness

The guard in `OrderResource.cancel` is one `if (request != null && request.isDryRun())` branch
that returns the review and stops. To confirm it actually matters, that branch was removed by
hand so every request fell through to `commit(...)` regardless of `dryRun`. With the guard gone,
a `dryRun=true` call on the seeded order silently cancelled it and published an event: `mvn -B
verify` failed `dryRunReviewsWithoutPersistingOrPublishingThenCommitSucceeds` (the order was
already `CANCELLED` by the time the "real" commit ran, so the second call returned 409 instead
of 200), and a live `./verify.sh` run fell from 23/23 to 17/23, failing every check from "the dry
run left the order row unchanged" onward: the dry run flipped the row, published to the topic,
and the subsequent intentional commit was rejected as a duplicate cancel. The branch was
restored and both suites reverified green. See `_plans/verification-log.md` for the exact run.

## Run

```bash
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it builds the service and the shared
domain model inside the image, starts Kafka, PostgreSQL, Apicurio, Keycloak, Ollama and LGTM
plus the service, runs the checks below and removes everything with `down -v`. Set `KEEP=1` to
leave the stack running for inspection.

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
PASS 7: apa-propose-preview-commit is healthy
PASS 8: the seeded order O-5001 starts PLACED
PASS 9: the seeded payment for O-5001 starts COMPLETED
PASS 10: a dryRun=true call on O-5001 succeeds with 200
PASS 11: the dry-run response reports the full review shape (before/after/refund/wouldEmit/reversible)
PASS 12: the dry run left the order row unchanged (still PLACED)
PASS 13: the dry run left the payment row unchanged (still COMPLETED)
PASS 14: the dry run published zero events for O-5001
PASS 15: the real commit for O-5001 (dryRun absent) succeeds with 200
PASS 16: the order flips to CANCELLED
PASS 17: the payment flips to REFUNDED
PASS 18: exactly one OrderCancelled record keyed by O-5001 (the commit, not the earlier dry run)
PASS 19: decision=REJECTED_BY_USER on O-5002 is a graceful 200 no-op
PASS 20: the REJECTED_BY_USER decision left the order row unchanged (still PLACED)
PASS 21: the REJECTED_BY_USER decision published zero events for O-5002
PASS 22: O-5002 is still cancellable afterwards: a real commit now succeeds
PASS 23: exactly one OrderCancelled record keyed by O-5002 (the commit, not the rejected decision)
PASS 24: a plain commit (no dryRun, no decision) on O-5003 succeeds with 200
PASS 25: exactly one OrderCancelled record keyed by O-5003
PASS 26: negative witness: a dry run on an already-cancelled order (O-5004) is rejected with 409
PASS 27: negative witness: committing an already-cancelled order (O-5004) is rejected with 409
PASS 28: negative witness: cancelling an unknown order is rejected with 404
28/28 checks passed
```

## Tests and checks in detail

`ProposePreviewCommitTest` (`mvn -B verify`) exercises the REST resource directly against Dev
Services Kafka and PostgreSQL: a dry run on the seeded order reports the full review shape, then
a real commit on the same order still succeeds (proving the dry run wrote nothing); a
`REJECTED_BY_USER` decision is a graceful no-op, then a real commit on the same order still
succeeds; a plain commit publishes exactly one event; a dry run and a commit against an
already-cancelled order are both rejected with 409; an unknown order id is rejected with 404.

`verify.sh` adds the live, container-level view: every infrastructure container healthy, the
seeded starting state, the same dry-run/commit/reject-decision behaviour observed directly in
PostgreSQL and Kafka, including the row-count and topic-offset assertions that are this
chapter's point.

## Experiments

Each row changes a single thing and names the checks that fail.

| Switch | Effect | Failing checks |
|---|---|---|
| Remove the `isDryRun()` guard in `OrderResource.cancel` so every call falls through to `commit` | A dry-run call silently cancels and publishes; the following real commit is rejected as a duplicate | 12-18 |
| Make `CancelReviewService.review` skip the lifecycle guard and always return a review | A dry run on an already-cancelled order (O-5004) reports a reviewable transition instead of 409 | 26 |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../OrderResource.java` | the agent-facing Quarkus REST surface: `POST /orders/{id}/cancel`, dispatching to preview, commit, or the REJECTED_BY_USER no-op |
| `src/main/java/.../CancelReviewService.java` | the read-only preview path: computes the `ReviewPayload` without writing |
| `src/main/java/.../CancelRefundRoute.java` | the Camel route: runs the commit saga, then publishes `OrderCancelled` keyed by order id |
| `src/main/java/.../CancelRefundSaga.java` | the `@Transactional` atomic commit saga against the `orders` and `payments` schemas |
| `src/main/java/.../ReviewPayload.java`, `NoActionResult.java`, `OrderCancelledEvent.java` | the three response shapes: preview, graceful no-op, and the commit event |
| `src/main/java/.../OrderNotFoundException.java`, `OrderNotCancellableException.java` | the two failure modes, shared by the preview and commit paths, mapped to 404 and 409 |
| `src/main/resources/db/migration/` | Flyway migrations for the `orders` and `payments` schemas, plus the seed |
| `compose.yaml` | includes the shared infrastructure and adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
