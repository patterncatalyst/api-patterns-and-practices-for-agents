---
title: "Testing Async Flows and the Human-in-the-Loop Gate"
order: 30
part: "Test and automate"
description: "An async, human-gated action is tested the way any contract is tested, by observing the durable effect under a bounded poll, never by reading the trigger's exit code."
duration: "25 minutes"
---

## Learning objectives

- Test an async, human-gated action by observing its durable effect instead of asserting on the trigger's return value.
- Read durable rows and Kafka topic offsets under a bounded poll, and confirm an absent effect holds across a bounded window.
- Prove exactly-once execution under redelivery, a terminal rejection and a dropped-decision no-op without ever reading an exit code.
- Recognize an exit-code assertion, an approve-by-default wait and a forked subject as the ways an async test stops testing the gate.

## Why this matters

A synchronous call returns its outcome, so a test can assert on the return value. An action that holds an irreversible step at a human-in-the-loop gate cannot: the trigger answers 202 and holds, and the real outcome shows up later, as a durable row and a published record, long after the HTTP call returned. A test that asserts on the trigger's response proves nothing, because that 202 comes back whether the gate held, approved, rejected, or dropped the decision. The outcome worth testing is the effect, and the effect is not in the response. It is in the gate row, the order and payment rows and the topic offsets, and it has to be read where it lands.

{% include excalidraw.html file="30-async-effect-observation" alt="A trigger answers 202 in every case while the real outcome lands later as a gate row, order and payment rows and topic offsets; the harness polls those effects and confirms a dropped decision leaves no effect across a bounded window" caption="Figure 30.1: The 202 is the same in every case; the durable effect is what distinguishes them" %}

## Pattern: Testing Async Flows and the Human-in-the-Loop Gate

### The problem

An async, human-gated action has no synchronous return value worth asserting on: the trigger accepts the request and holds, so a test on its 202 passes whether the gate held the action, executed it once, rejected it, or lost the decision entirely. The outcome that matters arrives out-of-band and lands in durable state, where a response-level assertion never looks.

### The solution

**An async, human-gated action is tested by observing its durable effect under a bounded poll, the gate row, the subject rows and the topic offsets, and by confirming that a dropped decision leaves no effect across a bounded window, never by reading the trigger's exit code.**

### How the test harness models it

The subject is a human-in-the-loop cancel-and-refund gate, reused unchanged: the harness builds the gate service's own image from its own build context and owns no application code. What it owns is an effect-observation harness that reads the gate's durable state across two channels, PostgreSQL rows through `psql` and Kafka topic offsets through the console consumer and producer, each read under a bounded retry rather than a bare exit code:

```bash
poll() { # retry every 2s until the command succeeds, or fail at the deadline
    local end=$((SECONDS + $1)); shift
    until "$@"; do (( SECONDS < end )) || return 1; sleep 2; done
}
holds_for() { # the command must stay true for the whole window
    local end=$((SECONDS + $1)); shift
    until (( SECONDS >= end )); do "$@" || return 1; sleep 2; done
}
```

Four scenarios exercise the gate through those effects. A trigger opens a `PENDING` gate and publishes exactly one pending-decision record keyed by the correlation id, while the order stays `PLACED`, its payment stays `COMPLETED` and nothing lands on the decisions topic yet. An `APPROVE` decision published to the decisions topic resumes the gate to `APPROVED_EXECUTED`, moves the order to `CANCELLED` and its payment to `REFUNDED`, and writes exactly one execution-log row. The same `APPROVE` redelivered leaves that count at one, so the exactly-once guard is observable and not assumed. A `REJECT` on a separate order terminates it as `REJECTED_BY_USER` with no cancel and no refund.

The dropped-decision case is the biting negative witness. A fresh order is triggered and its decision is never published:

```bash
check "dropped decision: O-19006 stays PENDING with no side effect for ${DROP_WINDOW}s" \
    holds_for "$DROP_WINDOW" gate_status_is O-19006 PENDING
check "dropped decision: O-19006 stays PLACED and COMPLETED (no cancel, no refund)" \
    no_side_effect O-19006
```

A dropped decision is the absence of an effect, which is exactly what effect-observation catches and an exit-code test cannot: the trigger returned 202 here just as it did for the approved and rejected orders, yet nothing should ever happen, and `holds_for` confirms nothing does across the window. When the gate is removed from the subject, so the trigger cancels and refunds immediately, the pause scenario's "no side effect before a decision" assertions fail, because the order is `CANCELLED` and the payment `REFUNDED` the instant the trigger returns and no pending-decision record is ever published.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must let an action's durable effect be observed after the trigger returns, so a test can judge an async, human-gated outcome that never arrives as a synchronous return value.
> **Contract shape:** operation intent "request an irreversible action and observe whether it happened"; inputs the action's subject id and a decision delivered out-of-band; response shape an immediate acceptance plus a later durable effect (a gate row, the changed subject rows, a published record), not an in-band result; error shape a dropped decision is a safe no-op held open, not a failure; idempotency key the correlation id, so a redelivered decision resolves to the same single effect.
> **Build it here:** "Using this harness's poll and holds_for helpers, add a scenario that triggers a cancel, approves it, then redelivers the same APPROVE and asserts the execution-log count for the correlation id stays one."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): trigger an async gated action, then poll a durable store and the decision topic for its effect, asserting the gate holds until a decision arrives and executes exactly once."
> Check: the triggered order stays PLACED and COMPLETED until a decision is published, and a redelivered APPROVE never produces a second execution.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack comes up with the gate service and the shared infrastructure, the harness triggers a cancellation and reads the durable effect across rows and topic offsets for each scenario, then confirms a dropped decision leaves the gate pending with no effect across the window, and the stack is torn down. It ends at 36/36.

Try:

- Publish the same `APPROVE` decision twice and watch the execution log stay at exactly one row for the correlation id.
- Trigger an order and never publish its decision: the gate holds `PENDING` and the order and payment stay untouched across the bounded window.
- Rebuild the subject so its tool cancels and refunds immediately instead of opening the gate, then rerun: the pause scenario's "no side effect before a decision" assertions fail.

## Anti-patterns

- **Asserting on the trigger's exit code.** A 202 comes back whether the gate held, approved, rejected or dropped the decision, so an exit-code assertion passes in every case and tests the gate in none of them.
- **An approve-by-default wait.** A test that treats an unanswered decision as an approval after an interval cannot tell a held gate from an executed one, and it hides exactly the dropped-decision case the harness exists to catch.
- **Forking the subject into the harness.** A copy of the gate service drifts from the real one, so the observed effects belong to a stale subject; building the service's own image from its own context keeps the observed effects those of the real subject.

## When not to use it

A fully synchronous operation that returns its outcome in the response does not need effect-observation: a direct assertion on the return value is simpler and faster, and polling durable state for an effect that is already in hand only adds latency. A flow with no durable record of its outcome, nothing in a table and nothing on a topic, cannot be tested this way at all, and the fix there is to give the flow an observable effect first, not to work around its absence.

## References

- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- Apache Kafka documentation: [kafka.apache.org/documentation](https://kafka.apache.org/documentation/)
- PostgreSQL documentation: [postgresql.org/docs](https://www.postgresql.org/docs/)
- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
