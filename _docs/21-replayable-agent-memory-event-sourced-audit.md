---
title: "Replayable Agent Memory / Event-Sourced Audit"
order: 21
part: "Build: asynchronous APIs"
description: "An event-sourced log gives an agent a replayable memory of what happened, and a human an audit trail of what the agent did."
duration: "20 minutes"
---

## Learning objectives

- Record both directions of an agent's own actions, an inbound tool call it consumed and an outbound model call it made, on one append-only log.
- Keep a live projection current by folding the log, so the projection is always a pure function of what the log actually recorded.
- Rebuild a projection from the log alone and confirm it reproduces the live one exactly.
- Recognize an in-place mutation, a one-directional log and a non-rebuildable projection as the ways an audit trail stops being trustworthy.

## Why this matters

An agent's own decisions are worth remembering for two different reasons at once: the agent itself may need to recall what happened to act consistently later, and a human needs to audit what the agent actually did, independent of whatever the agent's own state currently claims. A row that gets overwritten in place serves neither: the agent's memory of the past changes retroactively, and the audit trail stops being a trail at all, since whatever it said yesterday is gone the moment it says something different today. An append-only log, read the same way every time, is what keeps both uses trustworthy.

{% include excalidraw.html file="21-event-sourced-audit" alt="An append-only Kafka log folds into a live projection; a replay reads the same log from the beginning and must reproduce it exactly, while a direct mutation that bypasses the log breaks that agreement until the live projection is rebuilt from the untouched log" caption="Figure 21.1: The log is the durable record; rebuilding live from it erases a direct mutation's effect" %}

## Pattern: Replayable Agent Memory / Event-Sourced Audit

### The problem

A projection table that is also the system of record has no independent way to check whether it is correct: if a bug, a bad migration or a direct edit changes a row, nothing in the table itself says so, and nothing can rebuild it back to what actually happened, because what actually happened was never recorded separately from the table's current state.

### The solution

**Every action an agent takes is appended to a log it never mutates, in both directions, the tool call it consumed and the model call it made, and the live projection used day to day is nothing more than a fold of that log, rebuildable from it at any time.**

### How Quarkus and Camel model it

A Camel route is the only legitimate writer of the live projection, folding the log as it arrives:

{% raw %}
```java
from("kafka:" + AuditTopics.AGENT_ACTIONS + "?groupId={{audit.projection.group-id}}&autoOffsetReset=earliest")
        .routeId("audit-projection")
        .unmarshal(AuditJson.dataFormat())
        .bean(AuditProjectionStore.class, "appendLive");
```
{% endraw %}

The insert underneath is `ON CONFLICT (id) DO NOTHING`, keyed on each record's own id, so folding a redelivered or replayed record twice changes nothing: the fold is a pure function of the log. A replay reads the identical log from the beginning, with a disposable consumer group that never disturbs the live projection's own committed offsets, and folds it into a scratch table using the same logic:

```java
consumer.seekToBeginning(partitions);
// reads every committed record on the topic from the beginning into the target table
```

Because both the live projection and a replay fold the same immutable input the same way, they must always agree, unless something bypassed the fold and wrote to the live table directly. One demo-only endpoint does exactly that, gated off by default, to make the point concrete: it updates a projection row in place without appending a correcting record to the log. With that gate opened, a replay still reads only the untouched log, reconstructs the original detail, and no longer matches the now-corrupted live projection. Rebuilding the live table from the log alone, truncate then refold, erases the mutation's effect entirely, because the log it refolds from was never touched. The audit record itself derives its cost field from token counts at a fixed rate rather than capturing prompt or completion content, so what the log records is a deterministic summary, never text a model produced, keeping personal or sensitive content out of a log that is kept forever by design.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must record every agent action, inbound and outbound, on a log it never mutates, and must be able to prove a live projection is correct by rebuilding it from that log alone.
> **Contract shape:** operation intent "append this action to the log, then fold it into the live view"; inputs an action's direction, subject and a deterministic summary of what happened; response shape the live projection's current state; error shape a rejected mutation attempt against the projection, never a silent in-place change; idempotency key the record's own id, so folding it twice is a no-op.
> **Build it here:** "Using `AuditProjectionRoute` and `ReplayService` as the model, add a third audit direction to this log for a new kind of action, and confirm a replay still reproduces the live projection after it is folded in."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI with aiokafka: an append-only log of agent actions folded into a projection by an idempotent upsert, with a replay endpoint that rebuilds the projection from the log alone and compares it to the live one."
> Check: a replay from the beginning of the log always matches the live projection, until something writes to the live projection without appending to the log, and rebuilding from the log afterward always restores the match.

## Build, run, observe

```bash
./verify.sh
```

Run from the replayable-agent-memory-event-sourced-audit example, this appends one action's inbound and outbound records, confirms a replay from the beginning of the log reproduces the live projection, demonstrates the in-place mutation breaking that agreement, then rebuilds the live projection from the untouched log and confirms the match returns. It ends at 29/29.

Try:

- Call the shipment-advice endpoint for an order the fixture does not name and watch the fallback carrier and its fixed token counts land in both audit directions.
- Flip the mutate gate on, corrupt one record, then replay and read the mismatch before rebuilding.
- Read the committed JSON Schema for one audit record and compare it against a folded row's own shape.

## Anti-patterns

- **Mutating or deleting a log record.** The moment anything can change what the log says happened, the log stops being the thing a replay or an audit can trust; every correction has to be a new, appended record, never an edit to an old one.
- **Logging only one direction.** Recording only the tool calls an agent consumes, or only the model calls it makes, leaves half the picture unrecoverable: a full audit needs both what triggered the agent and what the agent itself decided to do.
- **A projection that cannot be rebuilt from the log.** If the only way to get the projection back to correct is restoring a backup, the log was never actually the system of record, no matter what it was called.
- **Capturing full prompt or completion content by default.** Logging a model's raw prose, rather than a derived, deterministic summary, turns a durable audit trail into a durable store of whatever sensitive content passed through the prompt, for however long the log is kept.

## When not to use it

A stateless step with no decision worth auditing, one with no branch, no model call and no action a human would ever need to question later, gains nothing from an append-only log built to support replay: a simple structured log line is enough, and building a fold-and-replay pipeline around it is overhead with no payoff. Reach for this pattern when either replay or an independently verifiable audit trail is actually needed, not as a default for every write.

## References

- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- OpenTelemetry Semantic Conventions for Generative AI systems: [opentelemetry.io/docs/specs/semconv/gen-ai](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
- Apache Kafka documentation: [kafka.apache.org/documentation](https://kafka.apache.org/documentation/)
- Apicurio Registry documentation: [apicur.io/registry/docs](https://www.apicur.io/registry/docs/)
