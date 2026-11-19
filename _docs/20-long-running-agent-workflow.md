---
title: "Long-Running Agent Workflow"
order: 20
part: "Build: asynchronous APIs"
description: "A long-running workflow with compensating steps carries an agent-triggered process safely across minutes or days, not just one request."
duration: "25 minutes"
---

## Learning objectives

- Run a multi-step, agent-triggered process as a saga, with each step declaring its own compensation instead of one shared rollback.
- Let a failure partway through unwind only the steps already enlisted, in reverse order, rather than treating the whole flow as one transaction.
- Write a compensation that is safe to call twice, since a coordinator can retry one.
- Recognize an all-or-nothing transaction assumption, a non-idempotent compensation and prompt-hidden orchestration as the ways a long-running workflow loses consistency.

## Why this matters

Reserving stock, taking payment and booking a carrier span three different concerns and, in a real deployment, three different services; nothing holds a single database transaction across all three. A process that reserves stock, then fails to take payment, cannot simply roll back the way one transaction would: by the time the failure happens, the reservation has already been visible to whatever else might have read it. The fix is not a bigger transaction, it is naming, in advance, exactly how to undo each step that might need undoing, and running that undo only for the steps that actually ran.

{% include excalidraw.html file="20-saga-compensation" alt="The outer Camel Saga block runs three nested steps in order, reserve stock, take payment, an agent books a carrier; a declined payment throws, and the saga compensates every step enlisted so far, in reverse order" caption="Figure 20.1: Every compensation is a guarded, idempotent UPDATE: calling it twice changes nothing the second time" %}

## Pattern: Long-Running Agent Workflow

### The problem

Treating a multi-step, multi-service process as if it ran inside one ACID transaction either locks resources across services for far longer than any one of them would tolerate alone, or quietly stops being transactional the moment a step crosses a process boundary, leaving a failure partway through with no defined way back to a consistent state.

### The solution

**Each step in the workflow declares its own compensation up front, and a failure at any point unwinds only the steps already enlisted, each by its own named compensation, in the reverse of the order they ran.**

### How Quarkus and Camel model it

The Camel Saga EIP expresses exactly this shape. An outer saga owns the workflow's lifecycle; each step is its own nested saga joining that lifecycle, with its own compensation endpoint:

```java
from("direct:fulfillOrder")
        .saga()
            .propagation(SagaPropagation.REQUIRES_NEW)
            .timeout(Duration.parse(timeout))
        .to("direct:reserveStock")
        .to("direct:takePayment")
        .to("direct:bookCarrier")
        .end();

from("direct:takePayment")
        .saga()
            .propagation(SagaPropagation.MANDATORY)
            .compensation("direct:refundPayment")
        .bean(service, "takePayment(${header.orderId})")
        .end();
```

Completion mode stays at its default, `AUTO`: if every step's call returns normally the saga completes with nothing to undo; the moment any step throws, the exception propagates back up and the saga compensates every step enlisted so far, in reverse order, instead of completing. The third step, booking a carrier, is a local langchain4j `AiService` backed directly by `quarkus-langchain4j-ollama`, never the Camel `langchain4j-agent` component, calling one `@Tool`; its compensation, `cancelBooking`, is declared exactly the same way the two deterministic steps before it are. The coordinator enlisting and compensating these steps is Camel's own in-memory `CamelSagaService`, used unconditionally, so no external coordinator process needs to run alongside the service.

Every compensation is a guarded `UPDATE` that only takes effect from the exact prior state it undoes:

```java
// releaseStock only restores stock from a row it finds in status STOCK_RESERVED
```

That guard is what makes a repeat call safe: a coordinator that retries a compensation, which Camel can do, finds nothing left in the state the guard checks for and changes nothing the second time. A declined payment is the one failure this pattern's checks drive: the stock reservation compensates, no payment row is ever written, and the carrier step, enlisted after the failing one, never runs at all, so it has nothing to compensate.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must carry a multi-step, agent-involved process to a consistent end state even when a step partway through fails, without holding one transaction across every step.
> **Contract shape:** operation intent "run this workflow to completion or leave no partial effect"; inputs the steps and, for each, its own compensation; response shape the workflow's final state, completed or compensated; error shape a failed step that propagates to the saga, never a bare exception the caller has to interpret; idempotency key none at the API boundary, since the compensation's own idempotence is what protects a retried undo.
> **Build it here:** "Using `OrderFulfillmentSagaRoute` and `FulfillmentService` as the model, add a fourth nested step to the saga with its own compensation, and confirm a failure after it compensates all four steps in reverse."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI: an explicit saga runner that enlists a compensation function per step and calls them in reverse order on failure, each written to be safe under a repeat call."
> Check: a failure after any step leaves only the steps already enlisted compensated, in reverse order, and calling any one compensation a second time changes nothing further.

## Build, run, observe

```bash
./verify.sh
```

Run from the long-running-agent-workflow example, this completes one clean order (stock reserved, payment taken, a carrier booked), then runs a second order whose payment is declined and confirms the stock reservation compensates while the payment and shipment tables stay untouched. It ends at 23/23.

Try:

- Set `PAYMENT_DECLINE_ABOVE=0.00` and watch every order's payment decline, so nothing ever books a carrier.
- Set `COMPENSATE=off` and run a declined order: the reserved stock stays reserved, a partial effect that survives the failure.
- Call `releaseStock` twice directly and confirm the second call changes nothing beyond what the first already did.

## Anti-patterns

- **Treating a multi-service flow as one ACID transaction.** A transaction that tries to span services either holds locks across process boundaries for an unacceptable duration, or is not actually transactional once it crosses one, leaving no real guarantee at all.
- **A non-idempotent or non-commutative compensation.** A compensation that assumes it only ever runs once breaks the moment a coordinator retries it after a timeout; it must either be safe to call twice or be checked against the exact state it expects to find.
- **No timeout on the saga.** A step that never returns, with nothing bounding how long the saga waits for it, leaves the workflow stuck indefinitely instead of failing and compensating.
- **Orchestration hidden in prompt text.** Writing the order of steps and their compensations only into a model's instructions, rather than into the saga's own structure, leaves the sequence dependent on the model reading and following it correctly every time.

## When not to use it

A flow with no coordinator at all, where each step reacts independently to what came before with nothing waiting to compensate it, is choreography, not a saga, and forcing it into this pattern adds structure the flow does not need. A single step with nothing to undo if it fails gains nothing from a saga either: wrapping one synchronous call in saga machinery pays the pattern's overhead for a rollback that was never going to be necessary.

## References

- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- Apache Camel documentation, the Saga EIP: [camel.apache.org/docs](https://camel.apache.org/docs/)
- Quarkus LangChain4j documentation: [docs.quarkiverse.io/quarkus-langchain4j/dev/index.html](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)
- Camel Quarkus documentation: [camel.apache.org/camel-quarkus/latest](https://camel.apache.org/camel-quarkus/latest/)
