# 30 Testing async flows and the human-in-the-loop gate

A scripts-only test harness that tests an async flow, and its human-in-the-loop gate, the same
way any other contract is tested: by OBSERVING THE EFFECT, not the exit code. There is no app
module here. The subject under test is chapter 19's cancel-and-refund gate, reused unchanged:
`compose.yaml` builds it straight from `19-human-in-the-loop-gate/Containerfile` (build context
`examples/`, so the shared domain model installs first), and this example owns no Java.

## Why an async, human-gated flow needs a different test

A synchronous call returns its outcome, so a test can assert on the return value. An async,
human-gated action cannot: the trigger (`POST /orders/{orderId}/cancel-request`) answers `202`
and holds the irreversible work at a gate until a human decision arrives on Kafka. The real
outcome shows up later, as a durable row and a topic record, long after the HTTP call returned.

So a test that asserts on the trigger's exit code proves nothing: `202` comes back whether or
not the gate ever held, approved, rejected, or quietly dropped the decision. The methodology
this harness teaches is to assert on the DURABLE EFFECT, under a bounded poll:

- gate, order and payment state, read from PostgreSQL with `psql` in the `apa-postgres` container
- topic offsets on `apa.orders.pending-decisions` and `apa.orders.decisions`, read and written
  with `kafka-console-consumer` and `kafka-console-producer` in the `apa-kafka` container

Every assertion retries until the effect appears (`poll`) or, for the dropped-event witness,
confirms the effect stays absent for a bounded window (`holds_for`). No bare exit codes.

## What the harness checks

1. Pause with no side effect. The trigger opens a `PENDING` gate and publishes exactly one
   pending-decision record keyed by the correlation id; the order stays `PLACED`, its payment
   stays `COMPLETED`, and nothing lands on the decisions topic yet.
2. Approve executes exactly once. An `APPROVE` decision event on `apa.orders.decisions` resumes
   the gate: the order becomes `CANCELLED`, its payment `REFUNDED`, and exactly one
   `orders.cancel_execution_log` row is written.
3. Idempotency. The same `APPROVE` redelivered leaves the execution log at exactly one row.
4. Reject is a terminal no-op. A `REJECT` on a different order terminates it as
   `REJECTED_BY_USER` with no cancel and no refund, ever.
5. Observability. A Tempo trace, a Prometheus metric and a Loki log line for the service.
6. The dropped-event negative witness (see below), half self-contained and half a live bite.

## The negative witness

A negative witness is a check that FAILS when the pattern's guard is removed. This harness
carries two halves.

### Dropped event (self-contained, in `verify.sh`)

A fresh order is triggered and its decision is NEVER published. The gate must stay `PENDING`
and the order untouched for a bounded window. A dropped decision is no effect, which is exactly
what effect-observation catches and an exit-code test cannot: the trigger returned `202` here
just as it did for the approved and rejected orders, yet nothing should ever happen. The harness
asserts the gate holds `PENDING`, the order stays `PLACED` and `COMPLETED`, and the execution log
stays empty for the correlation id.

### Removed gate (live bite, run by the orchestrator)

The second half is chapter 19's proven bite, run separately against the real build: rebuild ch19
with the gate removed (swap `gateService.proposeAndHold(...)` in `CancelProposalTool` for an
immediate cancel-and-refund), run this harness, and confirm check (1)'s "no side effect before a
decision" assertions FAIL, then revert. Check (1) is structured so the bite is observable: with
the gate removed, the order is `CANCELLED` and the payment `REFUNDED` the instant the trigger
returns, and no pending-decision record is ever published, so the "still `PLACED`", "still
`COMPLETED`" and "exactly one pending-decision record" checks all break. The run and the failing
check numbers are recorded in `_plans/verification-log.md`.

## Run

```bash
../../scripts/ollama-model.sh check   # or pull, the first time
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin. It builds ch19's service and the shared
domain model inside the image, starts Kafka, PostgreSQL, Apicurio, Keycloak, Ollama and LGTM plus
the service, runs the checks above and removes everything with `down -v`. Set `KEEP=1` to leave
the stack running for inspection.

This example has no `mvn` lane of its own: the unit-level view of the gate lives with ch19's own
tests. This harness is the live, cross-channel view: the propose-hold-decide round trip observed
through rows and topic offsets.

## The order ids and channels it uses

These are ch19's own seeded fixtures and contracts, reused unchanged.

| Name | Value |
|------|-------|
| Trigger | `POST /orders/{orderId}/cancel-request`, body `{"reason":"..."}`, answers `202` |
| Gate reader | `GET /orders/{orderId}/cancel-gate`, returns `status` and `correlationId` |
| Pending-decision topic | `apa.orders.pending-decisions`, keyed by the correlation id |
| Decision topic | `apa.orders.decisions`, keyed by the correlation id |
| Decision event | `{"correlationId":"...","decision":"APPROVE"\|"REJECT","decidedBy":"reviewer@example.com"}` |
| Tables | `orders.orders`, `payments.payments`, `orders.cancel_gate`, `orders.cancel_execution_log` |
| Gate statuses | `PENDING`, `APPROVED_EXECUTED`, `REJECTED_BY_USER` |
| O-19001 | cancellable, used for pause then approve then idempotency |
| O-19002 | cancellable, used for the reject no-op |
| O-19006 | cancellable, used for the dropped-event witness (never decided) |

## Layout

| Path | Purpose |
|------|---------|
| `compose.yaml` | includes the shared infrastructure and LGTM; builds ch19's service from its Containerfile |
| `verify.sh` | the effect-observation harness and the dropped-event negative witness |
| `../../contracts/30-async-gate-observation-points.md` | the observation points this harness asserts on |
