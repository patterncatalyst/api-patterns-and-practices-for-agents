---
title: "Instructive Error (RFC 9457)"
order: 10
part: "Build: synchronous APIs"
description: "An RFC 9457 problem response names what was wrong and what to try next, instead of a bare status code an agent cannot act on."
duration: "20 minutes"
---

A human who hits an error reads the surrounding page, checks a log, or asks
a colleague; an agent has only the text the error response actually
contains. A bare status code with no remedy gives that agent exactly one
fact, that something failed, and nothing to decide what to try next. The
same failure, described with what is actually wrong and what a caller can
legally do about it, turns a dead end into a next step.

## Pattern: Instructive Error (RFC 9457)

### The problem

An opaque error, a bare status code or a generic message with no remedy,
tells an agent only that a call failed. It cannot distinguish a validation
problem it could fix by changing its own input from a state conflict it
could fix by trying a different operation, or an unrecoverable failure it
should simply report back. Without that distinction in the error's own text,
an agent's only safe move is to give up, even when the actual fix was a
field away.

### The solution

**An RFC 9457 problem response names the type, title, status and detail of
what went wrong, plus a remedy member specific to that failure, so an
agent's only source of recovery information is a response that actually
has one.**

### How Quarkus and Camel model it

`OrderResource` serves `placeOrder`, `getOrder` and `cancelOrder` as plain
Quarkus REST operations, with Camel publishing `OrderPlaced` and
`OrderCancelled` on the event edge; the resource itself never builds an
error response. Every domain exception propagates up to
`OrderExceptionMappers`, the one place each error's shape is decided:

```java
@ServerExceptionMapper
public RestResponse<ConflictProblem> mapOrderNotCancellable(OrderNotCancellableException e) {
    return RestResponse.ResponseBuilder.create(RestResponse.Status.CONFLICT, ConflictProblem.of(e))
            .type(PROBLEM_JSON)
            .build();
}
```

Each of the three mapped exceptions carries its own remedy member, not just
the four RFC 9457 fields every problem shares. A not-found order names
`nextStep`; a validation failure names the offending `field`; a
not-cancellable order names `currentState` and `allowedTransitions`,
computed from the order's own `OrderStatus.canMoveTo` lifecycle rule, not a
hardcoded list:

```java
static ConflictProblem of(OrderNotCancellableException e) {
    return new ConflictProblem(
            "https://errors.patterncatalyst.dev/problems/order-not-cancellable",
            "Order not cancellable", 409, e.getMessage(),
            e.currentState().name(),
            e.allowedTransitions().stream().map(Enum::name).toList());
}
```

A seeded order sitting at `SHIPPED` demonstrates a non-empty remedy,
`allowedTransitions: ["DELIVERED"]`; cancelling an order a second time
demonstrates the terminal case, `currentState: "CANCELLED"` with
`allowedTransitions: []`. Both responses tell an agent exactly what is
legally still possible, not just that the call it tried is not.

{% include excalidraw.html file="10-instructive-error" alt="An opaque error returns a bare status code with nothing an agent can act on; an RFC 9457 problem response names what was wrong and a remedy specific to it, such as the current state and the transitions still allowed" caption="Figure 10.1: A remedy is not the same information as a status code" %}

Commenting out the three `@ServerExceptionMapper` annotations removes the
guarantee at its root: every domain exception then falls through to the
framework's own default response, a bare 500 served as `text/plain` with a
generic body, no `type`, no `detail`, no remedy member of any kind. The
distinction `OrderExceptionMappers` draws between a 404, a 400 and a 409
collapses into one undifferentiated failure an agent cannot act on.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must return enough information in an error response that an agent can decide its next move from the response text alone, never from a bare status code.
> **Contract shape:** operation intent "fail with a problem response that names what was wrong and what to try next"; inputs whatever the failing call already carried; response shape a problem body naming a stable type, a title, the status, a detail specific to this occurrence, and a remedy member specific to the failure kind; error shape is the response itself; no idempotency key needed.
> **Build it here:** "Using `OrderExceptionMappers` and `ConflictProblem` as the model, add a fourth domain exception and its own `@ServerExceptionMapper`, with a remedy member that names what the caller can legally try instead."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): map each domain failure to an RFC 9457 problem response carrying a remedy member specific to that failure, rather than letting the framework's default error handler return a bare status code."
> Check: every mapped domain failure returns a problem response naming a remedy specific to that failure kind, and removing the mapping for one failure degrades only that failure to a bare, remedy-free response, never the others.

## Build, run, observe

```bash
./verify.sh
```

Run from the instructive-error example, this brings up Kafka and PostgreSQL
alongside the service, triggers the not-found, validation and conflict
cases, and confirms each problem body carries its own remedy member and that
the three problem types are pairwise distinct. It ends at 15/15.

Try:

- Comment out all three `@ServerExceptionMapper` methods and rerun: every
  case returns a bare 500 as `text/plain` instead of its mapped problem.
- Cancel the seeded `SHIPPED` order and inspect the 409 body: it names
  `DELIVERED` as the only state still reachable from here.

## When not to use it

A remedy member specific to every failure is worth writing for a caller that
can actually act on it; an error path a human operator alone ever sees,
never an agent, gets less value from a structured remedy than from a stack
trace a person can read directly. Naming every legal next state also assumes
the lifecycle rule computing it stays accurate: a remedy that falls out of
sync with the rule it describes is worse than no remedy, because it tells a
caller something untrue with confidence.

## References

- RFC 9457: Problem Details for HTTP APIs
- JJ Geewax, *API Design Patterns*, Manning, 2021
- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- Apache Camel documentation: [camel.apache.org/docs](https://camel.apache.org/docs/)
