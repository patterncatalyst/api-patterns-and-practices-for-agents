---
title: "Human-in-the-Loop Gate"
order: 19
part: "Build: asynchronous APIs"
description: "An explicit gate holds an irreversible action for human approval, with the mechanism chosen to match how long that approval might take."
duration: "25 minutes"
---

## Learning objectives

- Hold an irreversible, high-value agent action at an explicit gate instead of letting the agent execute it on its own say-so.
- Resume a held action exactly once from a decision, even when that decision arrives more than once.
- Choose among the available hold mechanisms by how long an approval might realistically take and whether it must survive a restart.
- Recognize a gate an agent can route around, an approve-by-default timeout and uniform gating as the ways a human-in-the-loop check stops actually checking anything.

## Why this matters

An agent that can classify a cancellation as fraud and also hold the authority to cancel the order and refund the payment has combined a judgment call with an irreversible action, with nothing between the two. The fix is not to trust the judgment more, it is to split the two apart: the agent proposes what it would do and why, and a human decides whether it actually happens. The part that is easy to get wrong is not the proposal, it is the hold: an agent action that is "paused" only in the sense that nothing reads its result yet is not paused at all, it already ran.

{% include excalidraw.html file="19-hitl-gate" alt="The gate timeline from trigger to agent classification to a held PENDING row to a human decision that resumes it exactly once, compared against three other mechanisms for the same pattern across wait duration, durability, in-session versus out-of-band and who holds the state" caption="Figure 19.1: A Kafka gate durably outlasts a restart, at the cost of answering out-of-band" %}

## Pattern: Human-in-the-Loop Gate

### The problem

An agent that decides and acts in the same call has no seam for a human to intervene before the action takes effect; by the time anyone sees what happened, it already has. Adding a confirmation prompt the agent can choose to skip, or a check that only logs a warning after the fact, is not a gate, it is a note left for someone to read later.

### The solution

**An irreversible, high-value action is held open as a pending decision until an explicit human response arrives, and the mechanism that holds it is chosen by how long that response might take and whether the hold must survive a restart.**

### How Quarkus and Camel model it

The agent's one tool never performs the cancel-and-refund itself; it opens a gate and returns:

```java
@Tool("Open a human-in-the-loop decision gate proposing to cancel an order and refund its payment, "
        + "classified by a reasonCode (DUPLICATE_ORDER, FRAUD_SUSPECTED or CUSTOMER_REQUEST); "
        + "never cancels the order or refunds the payment itself")
public String proposeCancelAndRefund(String orderId, String reasonCode, String reasonText) {
    GateOpened opened = gateService.proposeAndHold(orderId, reasonCode, reasonText);
    producerTemplate.sendBody("direct:cancel-gate-publish-pending", toEvent(opened));
    return "cancel-refund gate opened for " + orderId;
}
```

`proposeAndHold` writes a `PENDING` row and touches neither the order nor the payment table; a Camel route publishes the proposal to `apa.orders.pending-decisions`, keyed by a correlation id. A decision on `apa.orders.decisions`, a plain Camel Kafka consumer, resumes the held row through one shared guard:

```java
private String claimGate(Connection c, String correlationId, String newStatus) throws SQLException {
    // SELECT ... FOR UPDATE, then only a PENDING row moves to newStatus; everything else returns null
}
```

Locking the row before checking its status is what makes a redelivered `APPROVE` safe: the first delivery's lock-check-update happens inside one transaction, so a second delivery of the exact same decision can never observe the row as still `PENDING`, and performs no side effect, approve or reject alike. `APPROVE` is the only path that ever reaches the cancel-and-refund; `REJECT` moves the row to a terminal status and writes nothing else.

Three other mechanisms hold an agent action the same way, for the same reason, trading differently on how long the wait can run and whether it survives a restart:

| Mechanism | Wait duration | Durable across restart | In-session or out-of-band | State held by |
|---|---|---|---|---|
| MCP Elicitation | seconds, one turn | no, lost on disconnect | in-session | the open MCP connection |
| `@HumanInTheLoop` (quarkus-langchain4j-agentic) | minutes, one process | no, in-process only | in-session | the suspended agentic workflow |
| Quarkus Flow `emitJson`/`listen` (`flowinstanceid`) | hours, across restarts | yes, by its flow instance id | out-of-band | the flow engine's own store |
| Kafka pending-decision gate (this pattern) | days, no deadline forced | yes, the gate row and topic | out-of-band | the PostgreSQL gate row |

MCP Elicitation confirms a mutation inline, in the same session a caller is already in, the right fit for a decision a human at the keyboard can make in seconds. `@HumanInTheLoop` suspends an in-process agentic workflow until a response arrives, still bound to that process's own lifetime. Quarkus Flow's durable workflow engine survives a restart by persisting the suspended flow under its own instance id. The Kafka pending-decision gate trades the in-session convenience of the first two for durability that outlasts the service entirely: the `PENDING` row and the two Kafka topics are the whole of the held state, so a decision that takes a day to arrive loses nothing if the service restarts in the meantime.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must hold an irreversible action open as a pending decision until an explicit human response arrives, performing the action exactly once however many times that response is delivered.
> **Contract shape:** operation intent "propose an irreversible action for approval"; inputs the action's subject and the agent's stated reason; response shape a pending decision with a correlation id, never the action's own result; error shape a decision for an unknown or already-decided correlation id is a safe no-op, not an error; idempotency key the correlation id, claimed exactly once regardless of redelivery.
> **Build it here:** "Using `CancelGateService.claimGate` as the model, add a second gated action on this service with its own pending-decision topic, sharing the same claim-locks-then-moves-to-terminal discipline."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI with aiokafka: a pending-decision table and topic that a propose call writes to and a decision consumer resumes, claiming each correlation id exactly once under a row lock."
> Check: the subject of the action is unchanged until a decision is published, and redelivering the same decision never performs the action a second time.

## Build, run, observe

```bash
./verify.sh
```

Run from the human-in-the-loop-gate example, this triggers a cancellation proposal, confirms the order and payment stay untouched while the gate is pending, publishes an approval and confirms the cancel-and-refund lands exactly once even when the same approval is redelivered, then confirms a rejection leaves no side effect at all. It ends at 39/39.

Try:

- Trigger a cancellation with a reason containing "fraud" and watch the fixture or rule classify it `FRAUD_SUSPECTED`.
- Publish the same `APPROVE` decision twice and confirm the execution log still shows exactly one execution.
- Trigger a cancellation on an already-cancelled order and watch it get rejected before any gate ever opens.

## Anti-patterns

- **A gate the agent can route around.** If any other path reaches the same irreversible effect without passing through the hold, the gate protects nothing; the tool in this pattern has no other way to cancel and refund than opening the gate first.
- **Approve-by-default on timeout.** Treating an unanswered decision as an approval after some interval turns the gate into a delay, not a check, for exactly the irreversible actions that most need a real answer.
- **Gating everything identically.** Holding a trivial, reversible action to the same scrutiny as an irreversible, high-value one trains whoever is reviewing requests to approve without reading them, which defeats the one thing a gate exists to provide.

## When not to use it

A reversible action, a cheap one, or one already audited somewhere else does not need a human in its path: the gate's cost is a wait of unknown length and a human's attention, and spending both on a mistake that is trivial to undo is waste the pattern is not meant to absorb. A latency budget no human can meet, a fraud check that must resolve in milliseconds, for instance, rules the pattern out regardless of how high the stakes are; that case needs a different control entirely, not a faster human.

## References

- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
- Quarkus LangChain4j documentation: [docs.quarkiverse.io/quarkus-langchain4j/dev/index.html](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)
- Apache Camel documentation, Kafka component: [camel.apache.org/docs](https://camel.apache.org/docs/)
- Apicurio Registry documentation: [apicur.io/registry/docs](https://www.apicur.io/registry/docs/)
