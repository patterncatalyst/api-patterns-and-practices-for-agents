---
title: "Agent-Behaviour Eval with Deterministic Replay"
order: 28
part: "Test and automate"
description: "A deterministic evaluator scores a recorded transcript of an agent's behaviour to PASS or FAIL, naming the first assertion it violates, never judging the model's own prose."
duration: "20 minutes"
---

## Learning objectives

- Turn an agent's behaviour on a task into a deterministic, repeatable PASS or FAIL check.
- Assert only captured, structured signals, the tool-call sequence, the budgets, the durable outcome, never the model's own prose.
- Score a live run and a replayed fixture through the exact same evaluator, so a build pipeline never needs a live model to verify the check still works.
- Recognize a vacuous PASS, an eval that asserts on wording, and a spec with no committed regression as the ways a behaviour eval stops actually checking anything.

## Why this matters

An agent that completes a task by calling three tools when one would do, or that calls the right tool with a malformed argument that happens not to crash, looks successful if the only thing anyone checks is whether the task eventually finished. A human reading the transcript might call that a near miss. A deterministic eval has no such latitude: it reads the same captured signals every time, a human reviewer or a passing grade from a different, equally non-deterministic model cannot. Committing a recorded transcript turns "did the agent do this acceptably" into a question with one fixed answer, scored the same way whether the transcript came from a live call this morning or a fixture committed months ago.

{% include excalidraw.html file="28-agent-behaviour-eval" alt="A live model run and a replayed fixture both produce the same transcript shape, a tool-call sequence, token usage and a durable outcome, which a deterministic evaluator scores against a committed eval spec by checking six ordered assertions and naming the first one a transcript violates" caption="Figure 28.1: The evaluator scores the same transcript shape whether it came from a live run or a replayed fixture" %}

## Pattern: Agent-Behaviour Eval with Deterministic Replay

### The problem

Judging whether an agent "did the task well" by reading its own summary of what it did is judging the agent by its own testimony: a model under evaluation has every reason, intentional or not, to describe its run favorably, and a reviewer who only reads prose has no independent signal to check that description against. Re-running the task against a live model on every build pipeline is no better: a live model's output varies run to run, so a check built on it is unreliable in CI for a different reason than being untrustworthy, and a transient failure becomes impossible to distinguish from a real regression.

### The solution

**A deterministic evaluator scores a recorded transcript of an agent's tool calls, budgets and durable outcome against a committed eval spec, asserting only structured signals the agent cannot talk its way around, and the same evaluator scores a live run and a replayed fixture identically.**

### How Quarkus and Camel model it

A Camel platform-http route is the trigger surface, nothing more: `EvalRoute` exposes an endpoint that scores whatever JSON-lines transcript is posted to it, and two more that run a committed good transcript and a committed regressed transcript through the same path. The transcript format is small and deliberate: a `run` line naming the order under task, zero or more `tool_call` lines each naming a tool, its arguments and whether that call was flagged an argument error, one `usage` line, and one `outcome` line naming the order's final status and whether the refund actually completed.

`BehaviourEvaluator` loads a committed eval spec once at startup and checks a transcript against it as six ordered assertions, stopping at the first one a transcript fails:

```json
{
  "expectedToolSequence": ["cancelAndRefund"],
  "maxToolCalls": 1,
  "maxArgErrors": 0,
  "maxTotalTokens": 900,
  "requireRefundCompleted": true,
  "requireFinalOrderStatus": "CANCELLED"
}
```

```java
static EvalVerdict evaluate(EvalSpec spec, Transcript transcript) {
    if (!onlyExpectedToolsWereCalled(spec, transcript)) {
        return EvalVerdict.fail("unexpected-tool-called");
    }
    if (transcript.toolCalls().size() > spec.maxToolCalls()) {
        return EvalVerdict.fail("call-budget-exceeded");
    }
    // ...argument-error budget, token budget, refund outcome, final order status, in that order
    return EvalVerdict.pass();
}
```

Every one of those six checks reads a captured, structured field: which tools were named, how many times, whether an argument was flagged bad, how many tokens a run spent, and what actually happened to the order and its refund. None of them reads a word the model wrote to explain itself; a free-text reason captured alongside a tool call is kept in the transcript for the audit record but never asserted against, because free text varies even on a fully compliant run. A committed `regressed-run` fixture calls the same tool three times, the first with an empty order id flagged as an argument error, spends more tokens than the budget allows, and never actually completes the refund; it scores FAIL, naming `call-budget-exceeded`, the first of its several problems the ordered assertions reach. A committed `good-run` fixture, one clean call within budget and a completed refund, scores PASS.

`EvalBean` bridges a route to the evaluator and to `EvalRecorder`, which writes a durable row per scored run, naming the verdict and, on FAIL, the violated assertion, alongside a Micrometer counter and a log line: a scored run is observable independent of which transcript produced it. On the live path, `LiveRunner` drives a local langchain4j `AiService` through its one tool once, then builds the identical `Transcript` shape from the captured `Result.toolExecutions()`, the structured record of what the model actually called, filtered by request name and reading each call's own result and arguments, never from anything the model said about its run. `BehaviourEvaluator` scores that transcript exactly the same way it scores a replayed fixture, so the default build pipeline runs the fixture path with no model required at all, and an operator who wants to see the real model's current behaviour sets a single flag to drive the live path against the same spec and the same budgets.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must score an agent's completed run against a committed spec by reading only the structured signals the run produced, never the model's own account of what it did, and must score a live run and a replayed recording identically.
> **Contract shape:** operation intent "score a recorded agent run against a committed behaviour spec"; inputs a transcript of the tool-call sequence, token usage and durable outcome; response shape a PASS or FAIL verdict naming the first violated assertion; error shape a malformed transcript is rejected before scoring, never silently scored as a pass; idempotency key none needed, scoring the same transcript twice against the same spec always returns the same verdict.
> **Build it here:** "Using `BehaviourEvaluator.evaluate` as the model, add a seventh ordered assertion to this example's eval spec, and commit a fixture transcript that fails only that one."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): a deterministic scorer that loads a committed behaviour spec and checks an ordered list of assertions against a recorded transcript's structured fields, never the model's own text, scoring a live run and a replayed fixture through the identical path."
> Check: editing only the recorded outcome of an otherwise-passing transcript flips the verdict to FAIL, naming the assertion that now fails, with the committed spec unchanged.

## Build, run, observe

```bash
mvn -B verify
./verify.sh
```

The unit path (5/5) scores the committed good and regressed transcripts with no model reachable at all, then edits the good transcript's own outcome to prove a PASS is not vacuous. `verify.sh` scores both transcripts over HTTP against the real compose stack, confirms the durable verdicts and observability signals, then mutates the good transcript's `refundCompleted` flag, resubmits it against the same spec, and confirms the verdict flips to FAIL before the unmutated transcript passes again. It ends at 23/23.

Try:

- Edit the committed `good-run` fixture's token usage above the spec's budget and watch the verdict flip to `token-budget-exceeded`.
- Add a second `tool_call` line to the good fixture and watch `call-budget-exceeded` fire before any later assertion is checked.
- Run with the live flag set and compare the real model's recorded transcript against the same committed spec.

## Anti-patterns

- **Scoring a model's own summary of its run.** A transcript's prose describing what happened is not a structured signal, and asserting on it lets a model's wording, not its behaviour, decide the verdict.
- **An eval spec with no committed regression.** A spec that has only ever scored a compliant transcript has never been seen to catch anything; a committed fixture that fails proves the ordered assertions actually stop at the right one.
- **A live-only eval in the default build path.** An eval that requires a live model call to run at all ties every build to a model's availability and its run-to-run variance, for a check that exists specifically to be repeatable.

## When not to use it

A task with no clean notion of a correct tool sequence or a durable outcome to check, open-ended summarization or free-form conversation, for instance, gives a deterministic evaluator nothing structured to assert against: forcing a fixed expected-sequence spec onto a task whose acceptable paths vary legitimately from run to run produces false failures, not a real signal. A one-off exploratory run that nobody intends to repeat also does not need a committed spec and a recorded fixture; the pattern earns its cost on a task that runs again and again and needs the same answer every time.

## References

- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- Quarkus LangChain4j documentation: [docs.quarkiverse.io/quarkus-langchain4j/dev/index.html](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)
- Apache Camel documentation, platform-http component: [camel.apache.org/docs](https://camel.apache.org/docs/)
