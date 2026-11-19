---
title: "Intent Endpoint (over CRUD)"
order: 3
part: "Build: synchronous APIs"
description: "One intent-named endpoint performs a whole business operation atomically, where a chatty sequence of CRUD calls leaves a window of partial state an agent can observe and act on incorrectly."
duration: "25 minutes"
---

## Learning objectives

- Collapse a chatty, CRUD-shaped sequence into one intent-named endpoint that performs the whole operation atomically.
- Model the REST resource to `direct:` route to saga boundary, so the HTTP layer never runs the transaction itself.
- Reject a cancellation on an order that is not cancellable with 409, and on an unknown order id with 404.
- Recognize an observable half-done state between two separate calls as the negative witness this pattern removes.

## Why this matters

A human workflow that cancels an order and refunds its payment is usually two
screens and two clicks, far enough apart in time that nobody notices the gap
between them. An agent given the same job over a CRUD-shaped API has to issue
the equivalent of two separate calls itself, in sequence, with nothing
stopping it from reading the order's state in between. One endpoint, named
for the whole business operation and backed by one transaction, removes the
gap instead of asking the caller to be careful about it.

## Pattern: Intent Endpoint (over CRUD)

### The problem

A chatty, CRUD-shaped surface forces a caller to sequence calls that belong
together: update the order's status, then create a refund, as two separate
round trips against two separate resources. Between those calls there is a
window where the order already reads CANCELLED and the payment still reads
COMPLETED, a state no business rule ever intended to exist but that a reader,
human or agent, can observe and act on. An agent that reads the order in that
window has no way to know the refund is still pending; it has only the
current row.

### The solution

**A single endpoint named for the business operation performs every step
inside it as one atomic unit, so a caller reading the result never sees it
half done.**

### How Quarkus and Camel model it

`POST /orders/{id}/cancel-and-refund` is the entire agent-facing surface, a
Quarkus REST resource, not a Camel `platform-http` route:

```java
@POST
@Path("/{id}/cancel-and-refund")
public Response cancelAndRefund(@PathParam("id") String id, CancelRequest request) {
    OrderCancelledEvent event = producerTemplate.requestBodyAndHeader(
            "direct:cancel-and-refund", request.reason(), "orderId", id, OrderCancelledEvent.class);
    return Response.ok(event).build();
}
```

The resource hands off to a `direct:` Camel route, which is where the EIP
boundary actually sits: Camel runs the saga and publishes the resulting event,
it never terminates the HTTP request itself.

```java
from("direct:cancel-and-refund")
        .process(exchange -> exchange.getMessage().setBody(
                saga.cancelAndRefund(orderId, reason)))
        .setHeader(KafkaConstants.KEY, simple("${body.orderId}"))
        .to("kafka:" + Topics.ORDERS_CANCELLED);
```

The saga itself is one `@Transactional` method that moves `Order.status` to
`CANCELLED` and `Payment.status` to `REFUNDED` against two schemas of the same
database, in the same method:

```java
@Transactional
public OrderCancelledEvent cancelAndRefund(String orderId, String reason) {
    ...
    updateOrderStatus(c, orderId, OrderStatus.CANCELLED);
    updatePaymentStatus(c, orderId, PaymentStatus.REFUNDED);
    return new OrderCancelledEvent(orderId, reason, Instant.now().toString());
}
```

Either both updates commit or neither does. Cancelling an order that is not
PLACED or PAID, including one already cancelled, is rejected with 409 rather
than silently re-run; cancelling an unknown order id is rejected with 404.
One intent-named tool also serves the forces an agent-facing contract has to
answer directly: tool-selection accuracy (one unambiguous name instead of a
sequence to get right), context budget and token economy (one tool
definition and one response instead of three), and the reasoning-and-
execution boundary, since the agent decides to cancel and refund, and the
application decides, atomically, whether that actually happens. What it
gives up is composability: a caller that only ever needed to cancel, without
a refund, has no narrower primitive to reach for here.

{% include excalidraw.html file="03-intent-endpoint" alt="Chatty CRUD cancels the order and refunds the payment as two calls with a crash window between them; the intent endpoint moves both in one call, one transaction" caption="Figure 3.1: One business operation, atomic or not" %}

Removing the saga's transactional boundary and forcing a failure between the
two updates breaks the guarantee directly: the order update commits on its
own, auto-committed outside any transaction, and the payment update never
runs, so the order ends up CANCELLED while the payment stays COMPLETED, the
exact partial state the pattern exists to prevent.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must let an agent cancel an order and refund its payment as one operation, never as a sequence the agent has to get right itself.
> **Contract shape:** operation intent "cancel and refund an order"; inputs an order id and a reason; response shape the resulting cancellation event; error shape a 409 naming why the order cannot be cancelled, a 404 for an unknown id; no idempotency key needed beyond the state check itself, since a repeat call on an already-cancelled order is rejected, not re-run.
> **Build it here:** "Using `OrderResource`, `CancelRefundRoute` and `CancelRefundSaga` as the model, add a parallel endpoint that reopens a CANCELLED order and re-authorizes its payment in the same `@Transactional` method, rejecting the call with 409 for any order that is not CANCELLED."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): a single operation that moves order and payment state together inside one database transaction, rejecting a second call on an order that is not in a cancellable state."
> Check: a client reading order and payment state immediately after the call never observes one changed without the other.

## Build, run, observe

```bash
./verify.sh
```

Run from the intent-endpoint example, this brings up Kafka, PostgreSQL and
the service, cancels a seeded order, and confirms the order and its payment
move together: a joined read never shows one changed without the other, and
exactly one cancellation event reaches its topic, keyed by order id. It ends
at 16/16.

Try:

- Remove the saga's transaction boundary and inject a failure between the two
  updates, then rerun: the order moves alone and the payment never does.
- Cancel the same order twice: the second call is rejected with 409 instead
  of running again.

## Anti-patterns

- **Chatty CRUD with an observable half-done window.** Updating the order and creating the refund as two separate calls leaves a state no business rule intended, readable in between.
- **Committing the two updates outside one transaction.** The pattern's own negative witness: the order update commits alone, auto-committed, and the payment update never runs, leaving the order CANCELLED and the payment COMPLETED.
- **One intent endpoint overloaded with flags for operations that are not one transaction.** Growing branching logic inside the endpoint to cover every combination a caller might want just relocates the chattiness it was meant to remove.

## When not to use it

An intent endpoint is worth less when the steps it would combine are
independent and get recombined in different ways by different
callers: a billing system that sometimes cancels without a refund, and
sometimes refunds without a cancellation, is better served by two narrower
operations than by one wide one that has to grow flags for every combination.
Collapsing operations that are not actually a single business transaction
just moves the complexity into the endpoint's own branching logic instead of
removing it.

## References

- JJ Geewax, *API Design Patterns*, Manning, 2021
- Anthropic, *Writing effective tools for AI agents*: [anthropic.com/engineering/writing-tools-for-agents](https://www.anthropic.com/engineering/writing-tools-for-agents)
- Quarkus REST guide: [quarkus.io/guides/rest](https://quarkus.io/guides/rest)
- Apache Camel documentation: [camel.apache.org/docs](https://camel.apache.org/docs/)
