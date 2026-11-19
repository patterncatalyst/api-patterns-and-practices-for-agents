---
title: "Propose/Preview/Commit"
order: 11
part: "Build: synchronous APIs"
description: "A preview step lets an agent see the effect of a write before committing it, so an irreversible action is never the first thing that happens."
duration: "25 minutes"
---

## Learning objectives

- Put a `dryRun` flag on the same intent endpoint instead of a separate preview path.
- Return a structured review that writes nothing and emits no event on a preview call.
- Treat `REJECTED_BY_USER` as a graceful no-op distinct from an error.
- Recognize a missing dry-run guard, where a preview silently commits, as the negative witness this pattern removes.

## Why this matters

An agent about to cancel an order and refund its payment cannot undo that
action once it runs. The only safe way to let an agent reach a decision like
that with confidence is to let it see the effect first, on the same
operation it would otherwise run blind, rather than trusting it to reason
correctly about an action it has never actually observed.

## Pattern: Propose, Preview, Commit

### The problem

An endpoint that only ever commits gives an agent one way to find out what a
write would do: run it. For an irreversible action, that means the first
time the effect is visible is also the moment it can no longer be undone. An
agent with no way to preview a commit either runs it speculatively, risking
an action nobody actually wanted, or avoids the operation altogether, which
leaves a legitimate task undone out of caution the system itself never
earned.

### The solution

**A dry-run flag on the same intent endpoint returns a structured review of
what committing would do and changes nothing, so an agent can inspect an
irreversible action's effect before choosing to commit it.**

### How Quarkus and Camel model it

`POST /orders/{id}/cancel` carries a `dryRun` flag and a `decision` field on
one body, rather than a separate `/preview` endpoint:

```java
public Response cancel(@PathParam("id") String id, CancelRequest request) {
    if (request != null && request.isRejectedByUser()) {
        return Response.ok(new NoActionResult(id, "REJECTED_BY_USER", "no action taken")).build();
    }
    if (request != null && request.isDryRun()) {
        return Response.ok(reviewService.review(id)).build();
    }
    return commit(id, request == null ? null : request.reason());
}
```

`CancelReviewService.review` computes the same existence and lifecycle
guard the commit path enforces, reads current state, and returns a
structured payload, never opening a write transaction and never reaching the
Camel route that publishes to Kafka:

```java
BigDecimal refundAmount = paymentAmount(c, orderId);
return new ReviewPayload(orderId, effectSummary, status.name(), OrderStatus.CANCELLED.name(),
        refundAmount, List.of("OrderCancelled"), List.of(), false);
```

A commit runs the same cancel-and-refund saga the intent-endpoint pattern
describes, through `CancelRefundRoute`, the Camel route that runs the
transactional saga and publishes exactly one `OrderCancelled` keyed by order
id. `decision: "REJECTED_BY_USER"` is a third, graceful outcome, not an
error: the caller reviewed a preview and chose not to proceed, so the call
returns a plain 200 no-op and writes nothing.

{% include excalidraw.html file="11-propose-preview-commit" alt="A fire-and-forget write commits the moment it is called, with no way to see the effect first; the same intent endpoint with a dryRun flag returns a structured review with zero writes, a commit applies it, and a REJECTED_BY_USER decision is a graceful no-op" caption="Figure 11.1: See the effect before it becomes permanent" %}

The whole guarantee rests on one guard in `OrderResource.cancel`, the branch
that returns the review and stops before `commit` ever runs. Removing it so
every request falls through to `commit` regardless of `dryRun` breaks the
pattern at its core: a `dryRun: true` call silently cancels the order and
publishes the event the caller only meant to preview, and an actual commit
attempted afterward is rejected as a duplicate cancel, because the order the
caller still believes is untouched has already moved.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must let an agent see the effect of an irreversible write before it happens, on the same operation it would otherwise commit blind, with a graceful way to decline after reviewing it.
> **Contract shape:** operation intent "preview or commit the same business operation, chosen by a flag on the request"; inputs the same operation's normal inputs plus a dry-run flag and a decision field; response shape a structured review (before and after state, the effect, what would publish) on preview, the committed result on commit, a graceful no-op result on decline; error shape the same 404 or 409 a commit attempt would return, since a preview runs the same guard; no idempotency key needed beyond the lifecycle check itself.
> **Build it here:** "Using `CancelReviewService` and `OrderResource.cancel` as the model, add a dry-run branch to a different write endpoint on this service, returning a structured review that reads current state and writes nothing."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): a dry-run flag on the same write operation that returns a structured preview of the effect without writing anything, with a graceful decline outcome distinct from an error."
> Check: a dry-run call never changes stored state and never publishes an event, no matter how many times it runs, and only a commit call does either.

## Build, run, observe

```bash
./verify.sh
```

Run from the propose-preview-commit example, this brings up Kafka and
PostgreSQL alongside the service, previews a cancel on a seeded order,
confirms nothing changed, commits the same cancel, confirms the order and
its payment both moved and exactly one event published, then exercises a
`REJECTED_BY_USER` decision as a graceful no-op. It ends at 28/28.

Try:

- Remove the `isDryRun()` guard in `OrderResource.cancel` and rerun: a
  preview call silently cancels the order, and the real commit after it is
  rejected as a duplicate.
- Preview an order twice in a row and inspect both responses: the second
  preview reports the same unchanged state as the first.

## Anti-patterns

- **A commit-only endpoint.** Forcing an agent to run an irreversible action speculatively just to see its effect leaves no way to inspect a write before it becomes permanent.
- **Dropping the dry-run guard.** Letting every request fall through to commit regardless of `dryRun` silently cancels the order the caller only meant to preview, the pattern's own negative witness.
- **A preview whose computed effect diverges from the commit's actual effect.** The review has to run the same guard and compute the same result the commit path produces, or the preview starts lying about what committing will do.

## When not to use it

A preview step doubles the paths through an endpoint that has to agree with
each other, the review has to compute the same effect the commit actually
produces, or the two quietly diverge and the preview starts lying about what
committing will do. An operation that is already cheap to undo, or one with
no meaningfully irreversible effect, gains little from a preview step beyond
the extra branch it adds to every call.

## References

- JJ Geewax, *API Design Patterns*, Manning, 2021
- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- Anthropic, *Writing effective tools for AI agents*: [anthropic.com/engineering/writing-tools-for-agents](https://www.anthropic.com/engineering/writing-tools-for-agents)
- Apache Camel documentation: [camel.apache.org/docs](https://camel.apache.org/docs/)
