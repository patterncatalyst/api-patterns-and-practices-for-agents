---
title: "Deterministic decisioning behind an agent tool"
order: 44
part: "Appendices"
description: "A committed Drools ruleset reimplements the refund-review decision behind an MCP tool, so a rules engine, not the model, decides whether a proposed action takes effect."
duration: "20 minutes"
---

## Learning objectives

- State why a model must never be the business rule a consequential action is judged against.
- Commit a decision as an ordered Drools ruleset, so the same proposal reaches the same verdict every time, independent of any model.
- Place the model-proposes, rule-engine-decides boundary at the one point an agent tool is allowed to cross it.
- Recognize a weakened or missing rule as the way a deterministic gate silently stops catching the violation it was built for.

## Why this matters

A model asked to decide whether a refund, a cancellation or any other consequential action may proceed is reasoning in natural language, and natural language reasoning is not the same model twice: a different phrasing of the same question, or a different model entirely, can reach a different answer even though the facts of the case have not moved at all. An agent tool that lets the model itself be the gate puts the one part of the system that most needs to be predictable, the actual decision on an action with real consequences, behind the one part of the system that is least predictable by design. The proposal can stay exactly as creative as a model makes it; the decision of whether it is allowed to happen cannot.

A rules engine answers a narrower question than a model does, and that narrowness is the entire point. It does not reason about intent or weigh the context a human reviewer might find persuasive. It evaluates a fixed, committed set of clauses against the facts it is handed and returns the same verdict every time those facts repeat, which is exactly the property a consequential decision needs and a model, by construction, cannot promise.

{% include excalidraw.html file="af-deterministic-decisioning" alt="An agent proposes a refund; the reviewRefund MCP tool hands the proposal to a committed Drools ruleset, which decides APPROVE or REJECT and names the failed clause; ReviewRecorder writes the verdict durably with a counter and a log line. The model is never on the decision path: it can only propose." caption="Figure F.1: The model proposes; the committed ruleset decides" %}

## The same decision, committed as a Drools ruleset

The decision reimplemented here is the refund-review decision from the prompt-plus-contract loop pattern earlier in Part 4: an agent proposes a refund for an order, an amount and a reason, and a committed rule decides `APPROVE` or `REJECT` from that proposal plus the order's own facts, its paid amount and its status. That earlier pattern reaches its verdict with a hand-rolled JSON-rule evaluator, a plain switch over an ordered list of clauses read from a committed file. The ruleset here reaches the identical verdict, naming the identical failed clause, from a committed DRL file fired by Apache Drools instead.

The three clauses carry over unchanged: the refund amount must not exceed the order's paid amount, the order's own status must still be refundable (`PAID` or `SHIPPED`, never already `CANCELLED` or `DELIVERED`), and the stated reason must be one of an allowed set. Each is one Drools rule, ordered by `salience` so the highest-priority clause a proposal fails is the one recorded:

```
rule "amount-exceeds-paid"
    salience 30
    when
        $v : Verdict( failedClause == null )
        RefundProposal( refundAmount > orderPaidAmount )
    then
        $v.setDecision(Decision.REJECT);
        $v.setFailedClause("amount-exceeds-paid");
        update($v);
end
```

A proposal and the facts it travels with become one mutable fact, `RefundProposal`; a second fact, `Verdict`, starts every evaluation at `APPROVE` and is flipped by whichever rule's constraint matches first. The `failedClause == null` guard on every rule stops a lower-salience clause from overwriting a verdict a higher-priority one already rejected, so a proposal that fails every clause at once is still named by the one clause a reader would check first.

A CDI bean compiles this file once, at startup, with Drools's `KieHelper` against an explicit classpath resource, never kmodule classpath scanning: a fast-jar's split classloader layout hides resources a scan expects to find on one flat classpath. Each call then opens a fresh `KieSession`, inserts the proposal and a new verdict, fires the rules, and reads the result back off the fact Drools updated:

```
KieHelper helper = new KieHelper();
helper.addResource(KieServices.get().getResources().newClassPathResource(resourcePath), ResourceType.DRL);
KieBase kieBase = helper.build();
```

Running a rule engine as a plain library this way, with no Kogito runtime and no DMN decision table, costs nothing a reader has not already paid for Drools itself: one compiled `KieBase`, reused across calls, and one session per evaluation. The committed file is the auditable artifact, readable and diffable by anyone, not a decision buried inside a conditional nobody can inspect without reading the surrounding code.

## Model proposes, rule engine decides

The MCP tool `reviewRefund` is the one place a model-originated proposal is allowed to reach this decision at all, and the boundary it draws is strict: the tool accepts an order id, a customer id, the order's status and paid amount, the proposed refund amount, and a stated reason, and it does nothing with any of them except build the fact Drools evaluates. Nothing about the tool's own code reasons about whether the refund seems reasonable. That judgment belongs entirely to the committed ruleset, fired the same way for every call regardless of what produced the proposal, a model, a script replaying a fixture, or a human filling in a form.

The verdict is recorded durably before it is returned: an append-only row naming the order, the proposed amount and reason, the decision and, on `REJECT`, the failed clause, alongside a counter and a log line. A caller does not have to trust a model's account of what happened; the row, the counter and the log line are the record, independent of whichever process asked for the review.

No model is reachable from this path at all. Nothing about the pattern changes if a model is never involved, because the entire point of committing the decision as a ruleset is to make the model's own judgment non-authoritative. Reintroducing a model call anywhere on this path, even just to double-check the rule engine's own verdict, would reopen exactly the nondeterminism the ruleset exists to close.

## The weakened-ruleset witness

A ruleset's clauses only prove anything if removing one changes the outcome. A second DRL file, `rules/refund-review-weakened.drl`, is the identical ruleset with the amount-ceiling rule deleted, committed only to demonstrate this and never used as the default. Both files ship inside the built image, so swapping between them needs only a container recreate with an overridden environment variable naming the classpath resource to compile, never a rebuild and never a hand-edited file on a running host.

Pointed at the weakened file and asked to review the exact same over-limit proposal a second time, the ruleset approves it. Nothing else about the request changed, not the order, not the amount, not the reason: the only variable was which committed file got compiled into the `KieBase`. Restoring the real file and replaying the identical proposal rejects it again, naming the same clause it named the first time. That flip, caused by nothing except which file was read, is the proof that the amount-ceiling rule, not some other property of the proposal, is what was catching the over-refund.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack comes up with this service and the shared infrastructure, a compliant and an over-limit proposal are reviewed through `reviewRefund` over the MCP protocol and their recorded verdicts checked in PostgreSQL, metrics and logs are confirmed in the observability stack, then the weakened-ruleset negative witness replays the over-limit proposal to watch the verdict flip and confirms restoring the ruleset flips it back. It ends at 19/19.

Try:

- Add a fourth rule to the committed ruleset and resubmit both proposals to see which one it newly rejects.
- Change the over-limit proposal's refund amount to exactly equal the order's paid amount and watch the verdict change.
- Submit the compliant proposal against the weakened ruleset and confirm its verdict is unchanged, since the rule removed from that ruleset was never the one approving it.

## Anti-patterns

- **A model judging its own proposal.** Asking the model that proposed an action whether the proposal is acceptable reintroduces the nondeterminism the rule engine exists to remove, no matter how the question is phrased.
- **A decision buried in conditional code instead of committed as a readable artifact.** A clause hidden inside an `if` statement nobody can diff against a file makes "what does the rule actually allow" a question only a code read can answer, and a rule engine's entire value is that the artifact itself answers it.
- **A weakened or silently dropped rule.** Removing a rule, or never adding one a new failure mode needs, leaves the engine approving exactly the proposals it was meant to catch, with no visible change to the code that calls it.
- **Treating the rule engine as a place to also run a model call "just to be safe."** A verdict that depends on a ruleset most of the time and a model the rest of the time is not deterministic any of the time; the whole value of committing a ruleset disappears the moment anything else can override it.

## References

- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- Apache Drools documentation: [drools.org](https://www.drools.org/)
- Anthropic, *Writing effective tools for AI agents*: [anthropic.com/engineering/writing-tools-for-agents](https://www.anthropic.com/engineering/writing-tools-for-agents)
