---
title: "The Prompt-plus-Contract Loop and Decision-Rule Review"
order: 26
part: "Consume"
description: "An agent proposes an action under a contract, and a deterministic decision-rule reviewer, not the model, decides whether it takes effect."
duration: "20 minutes"
---

## Learning objectives

- Gate a proposed agent action behind a deterministic reviewer evaluated from a committed rule, not a model call.
- Commit a decision rule as ordered clauses in data, so the same proposal reaches the same verdict independent of what model proposed it.
- Record a verdict and its failed clause durably, so a review is observable without re-deriving it from a model's own explanation.
- Recognize a weakened or missing clause as the way a deterministic gate silently stops catching the violation it was built for.

## Why this matters

An agent that proposes a refund, a cancellation, or any other consequential action is reasoning in natural language, and natural language reasoning is not the same model twice: ask the same question with a slightly different phrasing, or swap the underlying model, and the proposal can change even when the facts have not. Letting that same model also decide whether its own proposal is acceptable puts the one part of the system that most needs to be predictable, the actual gate on an action with real consequences, behind the one part of the system that is least predictable by design. The proposal can stay exactly as creative as a model makes it; the decision of whether it is allowed to happen cannot.

{% include excalidraw.html file="26-decision-rule-review" alt="An agent proposes a refund; DecisionRuleReviewer evaluates it against the committed rule's ordered clauses and records APPROVE or REJECT with the failed clause; pointing the same reviewer at the weakened rule, with the amount-ceiling clause removed, flips the identical violating proposal from REJECT to APPROVE, and restoring the rule rejects it again" caption="Figure 26.1: The proposal varies; the committed rule and its verdict do not" %}

## Pattern: Decision-Rule Review

### The problem

A model asked to both propose an action and judge its own proposal is marking its own work: whatever bias or inconsistency shaped the proposal is free to shape the judgment of it too, and there is no way to tell, from the outside, whether a given approval reflects the facts of the case or just a model's agreeable mood on that particular call. A review step that itself calls a model inherits the same nondeterminism the review was supposed to remove, and a failure found this way is nearly impossible to reproduce: rerunning the same case can quietly reach a different verdict.

### The solution

**A committed decision rule, evaluated by a deterministic reviewer against an agent's proposal and the facts of the case, decides the verdict, so the same proposal reaches the same verdict every time independent of which model, or none, proposed it.**

### How Quarkus and Camel model it

`POST /review`, a Camel platform-http route, takes a recorded agent run as its JSON body, a refund proposal (amount, reason) and the order's own facts (status, paid amount) together as one `RefundProposalRun`:

```java
from("platform-http:/review?httpMethodRestrict=POST")
        .routeId("review-refund-proposal")
        .unmarshal().json(RefundProposalRun.class)
        .process(exchange -> {
            RefundProposalRun run = exchange.getIn().getBody(RefundProposalRun.class);
            exchange.getMessage().setBody(reviewBean.review(run));
        })
        .marshal().json();
```

`ReviewBean` is a thin bridge: evaluate, then record. `DecisionRuleReviewer` loads the committed rule once at startup and evaluates clauses in order, returning the first one a proposal fails as the verdict's failed clause:

```java
static Verdict evaluate(DecisionRule rule, RefundProposalRun run) {
    for (RuleClause clause : rule.clauses()) {
        if (!holds(clause, run)) {
            return Verdict.reject(clause.id());
        }
    }
    return Verdict.approve();
}

private static boolean holds(RuleClause clause, RefundProposalRun run) {
    return switch (clause.type()) {
        case "amountNotGreaterThanPaid" -> run.refundAmount().compareTo(run.orderPaidAmount()) <= 0;
        case "orderStatusIn" -> clause.allowed().contains(run.orderStatus().name());
        case "reasonIn" -> clause.allowed().contains(run.reason());
        default -> throw new IllegalStateException("unknown clause type: " + clause.type());
    };
}
```

The rule itself is committed as data, not code: an ordered list of clauses, the real three being that the refund amount must not exceed the order's paid amount, that the order's status must still be refundable, and that the stated reason must be one of an allowed set. This is a hand-rolled JSON-rule evaluator, not Drools or DMN: a plain switch on each clause's type is all three clauses here need, and nothing about the pattern requires a rule engine to hold. `ReviewRecorder` writes the outcome durably, an append-only row naming the order, the full proposal, the verdict and, on rejection, the failed clause, alongside a Micrometer counter and a log line, so a review is observable without re-reading a model's own account of what it decided.

No model is on this path at all. The proposals reviewed here are two committed fixtures, a compliant run and a violating one, standing in for what an agent would otherwise propose live. Since the reviewer is deterministic by construction, a live model call here would add nothing to the example's own assertions; the entire point of this gate is to make the model's judgment non-authoritative, so reintroducing one would undercut the lesson rather than illustrate it.

The weakened-reviewer witness is what proves the gate is actually doing the work its clauses claim to do. A second committed rule file is the same three clauses with the amount-ceiling clause removed, read only when `review.rule.path` is overridden to point at it:

```
review.rule.path=../../contracts/26-refund-review-rule.json
%prod.review.rule.path=${REVIEW_RULE_PATH:/deployments/contracts/26-refund-review-rule.json}
```

Pointed at the weakened file and asked to review the exact same violating proposal again, the reviewer wrongly approves it. Restoring the real rule path and replaying the identical proposal rejects it again, naming the same clause it named the first time. That flip, caused by nothing except which rule file was read, is the proof that the amount-ceiling clause, not some other property of the proposal, is what was catching the over-refund.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** an agent's proposed action must pass a deterministic gate, evaluated from a committed rule against the proposal and the case's own facts, before it can take effect, with the same proposal always reaching the same verdict regardless of which model produced it.
> **Contract shape:** operation intent "review a proposed action against a committed rule and record the verdict"; inputs the proposal's own fields plus whatever facts the rule's clauses need; response shape an `APPROVE` or `REJECT` verdict naming the first failed clause on rejection; error shape a 400 for a malformed proposal, never a silent approval; idempotency key not needed, a review has no side effect beyond its own durable record.
> **Build it here:** "Using `DecisionRule`, `RuleClause` and `DecisionRuleReviewer` as the model, add a fourth clause type to the committed rule (for example a maximum refund count per customer) and confirm the compliant and violating fixtures still resolve the same way."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): a committed JSON rule of ordered clauses, evaluated by a plain function against a proposal and the case's facts, returning the first failed clause rather than calling a model to decide."
> Check: replaying the identical violating proposal against the rule with one clause removed flips the verdict from REJECT to APPROVE, and restoring that clause flips it back, with no other input changed.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack comes up with this service and the shared infrastructure, the compliant and violating fixtures are submitted and their recorded verdicts checked in PostgreSQL, traces, metrics and logs are confirmed in the observability stack, then the weakened-rule negative witness replays the same violating proposal to watch the verdict flip and confirms restoring the rule flips it back. It ends at 16/16.

Try:

- Add a fourth clause to the committed rule and resubmit both fixtures to see which one it newly rejects.
- Change the violating fixture's refund amount to exactly equal the order's paid amount and watch the verdict change.
- Submit the compliant fixture against the weakened rule and confirm its verdict is unchanged, since the clause removed from that rule was never the one approving it.

## Anti-patterns

- **A model reviewing its own proposal.** Asking the same model that proposed an action whether the proposal is acceptable reintroduces the nondeterminism the review step exists to remove.
- **A rule compiled into conditional code instead of committed as data.** Burying a clause inside an `if` statement nobody can diff against a reviewable artifact makes "what does the rule actually allow" a question only a code read can answer.
- **A weakened or silently dropped clause.** Removing a clause, or never adding one a new failure mode needs, leaves the reviewer approving exactly the proposals it was meant to catch, with no visible change to the code around it.

## When not to use it

A proposal whose acceptability cannot be reduced to a finite set of clauses over structured facts, one that needs human judgment about intent or context a rule cannot encode, gains nothing from forcing that judgment into a rule file; routing it to a human reviewer instead of a deterministic one fits that case better. A system with no consequential action to gate in the first place has no proposal worth reviewing at all.

## References

- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- Apache Camel documentation: [camel.apache.org/docs](https://camel.apache.org/docs/)
- Anthropic, *Writing effective tools for AI agents*: [anthropic.com/engineering/writing-tools-for-agents](https://www.anthropic.com/engineering/writing-tools-for-agents)
