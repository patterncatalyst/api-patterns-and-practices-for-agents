# Async gate observation points (chapter 30)

The observation points `examples/30-testing-async-flows-and-hitl-gate/verify.sh` asserts on. An
async, human-gated flow has no synchronous return value worth testing: the trigger answers `202`
and holds. These are the durable effects that stand in for a return value, each read under a
bounded poll, never as a bare exit code. The subject is chapter 19's cancel-and-refund gate,
reused unchanged; every name below is ch19's own.

## Durable state (PostgreSQL)

| Observation point | Query | Effect it witnesses |
|---|---|---|
| gate status | `SELECT status FROM orders.cancel_gate WHERE correlation_id = ?` | the gate held (`PENDING`), approved (`APPROVED_EXECUTED`) or rejected (`REJECTED_BY_USER`) |
| order status | `SELECT status FROM orders.orders WHERE id = ?` | the cancel side effect (`PLACED` to `CANCELLED`) |
| payment status | `SELECT status FROM payments.payments WHERE order_id = ?` | the refund side effect (`COMPLETED` to `REFUNDED`) |
| execution-log count | `SELECT COUNT(*) FROM orders.cancel_execution_log WHERE correlation_id = ?` | exactly-once execution (the count stays 1 under redelivery) |

The gate reader `GET /orders/{orderId}/cancel-gate` exposes `status` and `correlationId` over
HTTP for the same gate row, so the harness reads the correlation id from the API and the effects
from the tables.

## Durable state (Kafka topic offsets)

| Observation point | Topic | Key | Effect it witnesses |
|---|---|---|---|
| pending-decision published | `apa.orders.pending-decisions` | correlation id | the proposal was held, not executed (exactly one record) |
| decision consumed | `apa.orders.decisions` | correlation id | the resuming decision (`APPROVE` or `REJECT`), zero until one is published |

Decision event shape, produced with the same key and body `CancelDecisionRoute` consumes:

```json
{"correlationId": "...", "decision": "APPROVE", "decidedBy": "reviewer@example.com"}
```

`decision` is `APPROVE` or `REJECT`.

## Observability (LGTM)

| Observation point | Source | Query shape |
|---|---|---|
| trace | Tempo | `service.name = human-in-the-loop-gate` |
| metric | Prometheus | `target_info{service_name="human-in-the-loop-gate"}` |
| log line | Loki | `{service_name="human-in-the-loop-gate"} |= "tool invoked: proposeCancelAndRefund"` |

The log assertion matches the tool's own emitted line, not any model prose, so the check stays
deterministic whichever decision path (real model or fixture) classified the reason.

## Why these points, and not the exit code

Each check is a `poll` (retry until the effect appears) or, for the dropped-event witness, a
`holds_for` (confirm the effect stays absent across a bounded window). An exit-code assertion
would pass whether or not the gate held, since the trigger returns `202` in every case:
approved, rejected, and dropped alike. The removed-gate bite is observable precisely because the
pause point (1) reads these effects: with the gate removed, the order and payment change before
any decision and no pending-decision record is published.
