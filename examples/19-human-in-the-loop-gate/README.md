# 19 Human-in-the-loop gate

A cancel-and-refund service that proves chapter 19's thesis: an irreversible, high-value agent
action is held at an explicit gate until a human decision arrives, never executed on the agent's
own say-so. `POST /orders/{orderId}/cancel-request` (the trigger) checks the order exists and can
legally be cancelled, then hands the stated reason to `CancelProposalAdvisor`, a local langchain4j
`AiService` backed directly by `quarkus-langchain4j-ollama` (never the Camel `langchain4j-agent`
component). The advisor classifies the reason (`DUPLICATE_ORDER`, `FRAUD_SUSPECTED`, otherwise
`CUSTOMER_REQUEST`) and calls its one tool, `CancelProposalTool.proposeCancelAndRefund`, which
never cancels the order or refunds the payment itself: it writes a `PENDING` row to
`orders.cancel_gate` and publishes the proposal to `apa.orders.pending-decisions`, keyed by a
correlation id. That is the entire extent of the agent's action.

A decision (`APPROVE` or `REJECT`, the same correlation id) on `apa.orders.decisions` resumes the
held proposal: `CancelDecisionRoute`, a plain Camel Kafka consumer, hands it to
`CancelDecisionBean`, which resolves it through `CancelGateService`. `APPROVE` performs the
cancel-and-refund side effect exactly once, even if the same `APPROVE` is redelivered. `REJECT`
terminates the proposal as `REJECTED_BY_USER`, writing nothing. Both outcomes share one guard:
`CancelGateService.claimGate` locks the gate row (`SELECT ... FOR UPDATE`) and only ever moves a
`PENDING` row to a terminal status; a redelivered decision finds the row already terminal and
performs no side effect, approve or reject alike.

This chapter's runnable mechanism is the Kafka-backed pending-decision/decision gate: durable,
deterministic, and provable with `verify.sh`'s topic-offset and row-state checks. The chapter
prose (not built here) compares this to MCP Elicitation and `@HumanInTheLoop`/Quarkus Flow as two
other mechanisms for the same pattern; this example builds only the Kafka gate.

## MODEL=off: the fixture lane

`agent.cancelgate.model-source` picks the decision path. `ollama` (the default, and what compose
and the live `verify.sh` always use) lets the real model classify the reason and call the tool
itself. `fixture` (forced by the `%test` profile, so `mvn -B verify` and CI never need a model)
routes the same reason text to `FixtureCancelAdvisor`, which replays a recorded classification
from the committed `src/main/resources/fixtures/cancel-reason-overrides.jsonl` instead of calling
Ollama, falling back to the same keyword rule `CancelProposalAdvisor`'s system prompt states
(`duplicate` to `DUPLICATE_ORDER`, `fraud` to `FRAUD_SUSPECTED`, otherwise `CUSTOMER_REQUEST`) for
any order id the fixture does not name. Either path ends at the same
`CancelProposalTool.proposeCancelAndRefund`, so the gate it opens is identical whichever path
produced the classification. The order-existence and cancellability checks run in
`CancelRequestResource` before the agent is ever called, so the 404/409 negative cases never
depend on model behaviour on either path.

## The negative witness

The guard is the hold itself: `CancelProposalTool.proposeCancelAndRefund` calls
`CancelGateService.proposeAndHold`, which only ever writes a `PENDING` row and returns, never
touching `orders.orders` or `payments.payments`. To confirm this guard actually matters, the one
call was changed by hand to skip the hold and perform the cancel-and-refund immediately on the
trigger instead (no `PENDING` row, no pending-decision event, no decision ever recorded). With
the gate removed, a fresh `./verify.sh` run broke exactly the "paused, no side effect before a
decision" checks: `O-19001` came back `CANCELLED` (and its payment `REFUNDED`) immediately after
the trigger, before any decision was published, and the pending-decision topic carried nothing
for its correlation id. The hand edit was reverted and `./verify.sh` reverified green. See
`_plans/verification-log.md` for the exact run and the failing check numbers.

## Run

```bash
../../scripts/ollama-model.sh check   # or pull, the first time
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it builds the service and the shared
domain model inside the image, starts Kafka, PostgreSQL, Apicurio, Keycloak, Ollama and LGTM plus
the service, runs the checks below and removes everything with `down -v`. Set `KEEP=1` to leave
the stack running for inspection.

Run the tests alone (Quarkus Dev Services starts Kafka and PostgreSQL containers; no compose
stack and no Ollama needed, since the `%test` profile forces the fixture decision path). Install
the shared domain model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Expected output

```
PASS 1: apa-kafka is healthy
...
PASS 7: apa-human-in-the-loop-gate is healthy
PASS 10: the artifact apa.orders.decisions exists in group apa
PASS 11: the artifact's compatibility rule reads back as BACKWARD
PASS 14: the gate for O-19001 is PENDING
PASS 15: O-19001 is still PLACED: no side effect before a decision
PASS 16: O-19001's payment is still COMPLETED: no refund before a decision
PASS 20: the gate for O-19001 resumes to APPROVED_EXECUTED
PASS 21: O-19001 is now CANCELLED
PASS 22: O-19001's payment is now REFUNDED
PASS 23: exactly one execution is logged for the correlation id
PASS 26: the execution log still shows exactly one execution for the correlation id
PASS 30: the gate for O-19002 resumes to REJECTED_BY_USER
PASS 31: O-19002 is still PLACED: REJECT performs no side effect
PASS 34: an already-cancelled order (O-19003) is rejected with 409
...
39/39 checks passed
```

## Tests and checks in detail

`CancelGateTest` (`mvn -B verify`) exercises the whole propose-hold-decide path against Dev
Services Kafka and PostgreSQL, MODEL=off: a fixture-named order is classified with the recorded
reasonCode even when the keyword rule would disagree; an order the fixture does not name falls
back to the rule in all three directions; the trigger pauses at the gate with no side effect yet;
an `APPROVE` decision applies the cancel-and-refund exactly once, proven by redelivering the same
`APPROVE` and confirming the execution log and both rows stay unchanged; a `REJECT` decision
terminates cleanly with no side effect ever; an unknown or already-cancelled order is rejected
without ever opening a gate.

`verify.sh` adds the live, real-model view: the same propose-hold-decide round trip through the
real `CancelProposalAdvisor` and Ollama, the Apicurio contract registration, topic-offset checks
on both channels, OTel signals reaching Tempo, Prometheus and Loki, and the negative witness
(demonstrated separately, by hand; see above).

## Experiments

Each row changes a single thing and names the checks that fail.

| Switch | Effect | Failing checks |
|---|---|---|
| `CancelProposalTool.proposeCancelAndRefund` calls a direct cancel-and-refund instead of `gateService.proposeAndHold` | The proposed action fires immediately on the trigger, no decision ever recorded | the "paused, no side effect before a decision" checks (the bite recorded in `_plans/verification-log.md`) |
| `CancelGateService.claimGate` drops its `FOR UPDATE` lock and the `PENDING`-only guard | A redelivered `APPROVE` could re-execute the cancel-and-refund | the idempotency checks (execution log count stays 1) |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../CancelRequestResource.java` | the trigger (`POST /orders/{id}/cancel-request`) and the gate reader (`GET .../cancel-gate`); validates before the agent is ever called |
| `src/main/java/.../CancelProposalAdvisor.java` | the langchain4j `AiService` interface (quarkus-langchain4j-ollama), registered with the one tool |
| `src/main/java/.../FixtureCancelAdvisor.java` | the MODEL=off decision path: reads `fixtures/cancel-reason-overrides.jsonl`, falls back to the keyword rule |
| `src/main/java/.../CancelProposalTool.java` | the one `@Tool`: opens the gate, publishes the pending-decision event; the bite's edit point |
| `src/main/java/.../CancelGateService.java` | the gate's state machine: propose-and-hold, and the atomically-claimed approve/reject resume |
| `src/main/java/.../CancelGatePublishRoute.java` | the `direct:` route that publishes a held proposal to `apa.orders.pending-decisions` |
| `src/main/java/.../CancelDecisionRoute.java`, `CancelDecisionBean.java` | the Kafka consumer on `apa.orders.decisions` and its resolver |
| `src/main/resources/fixtures/cancel-reason-overrides.jsonl` | the committed, recorded classifications `FixtureCancelAdvisor` replays |
| `src/main/resources/db/migration/` | Flyway migrations: `orders.orders`, `payments.payments`, `orders.cancel_gate`, `orders.cancel_execution_log` |
| `compose.yaml` | includes the shared infrastructure and adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
