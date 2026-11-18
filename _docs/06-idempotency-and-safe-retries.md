---
title: "Idempotency and Safe Retries"
order: 6
part: "Build: synchronous APIs"
description: "A required Idempotency-Key header, enforced by a server-side Idempotent Consumer, replays the first response byte for byte on a repeat key and rejects the same key reused with a different body, where an unguarded retry places a second order."
duration: "25 minutes"
---

An agent that calls a write endpoint and gets a timeout has exactly two
choices: give up, or retry. Giving up leaves a task unfinished for no reason
a human would accept; retrying, against an endpoint with no safeguard, risks
placing the same order twice because the first call may have actually
succeeded before the timeout arrived. Safety here cannot depend on the
caller's own discipline, because an agent retrying automatically has no way
to know, from the timeout alone, whether the first attempt landed.

## Pattern: Idempotency and Safe Retries

### The problem

A write endpoint with no idempotency guarantee treats every incoming request
as new, whether it is actually new or a retry of a call whose response never
arrived. A caller that retries after a timeout has no way to distinguish "the
first attempt failed, try again" from "the first attempt succeeded, the
response was lost"; without a server-side guard, the second case produces a
duplicate order, a duplicate charge, and a duplicate event on every topic
downstream of the write.

### The solution

**A required idempotency key, enforced by a server-side guard backed by
durable storage, replays the first response for a repeat key and rejects a
repeat key carrying a different body, so a caller never has to trust its own
retry discipline.**

### How Quarkus and Camel model it

`POST /orders` requires an `Idempotency-Key` header, served by Quarkus REST,
not Camel `platform-http`; the resource hands off to a `direct:` route where
the actual guard lives, the Idempotent Consumer EIP backed by a JDBC
idempotent repository keyed on that header:

```java
from("direct:place-order")
        .idempotentConsumer(header(IDEMPOTENCY_KEY_HEADER), repository)
        .skipDuplicate(false)
        .choice()
            .when(exchangeProperty(Exchange.DUPLICATE_MESSAGE).isEqualTo(Boolean.TRUE))
                .process(exchange -> exchange.getMessage().setBody(
                        saga.replayOrReject(key, request)))
            .otherwise()
                .process(exchange -> exchange.getMessage().setBody(
                        saga.placeNewOrder(key, request)))
        .end();
```

`skipDuplicate(false)` keeps a repeat key flowing through the route instead
of being silently dropped, so the route can branch on
`Exchange.DUPLICATE_MESSAGE` and reply with the right body rather than none
at all. A first-time key runs `PlaceOrderSaga.placeNewOrder`: one `Order` row
and its stored response are written in a single `@Transactional` method,
then the route publishes one event keyed by order id. A repeat key never
runs the saga again; `PlaceOrderSaga.replayOrReject` loads the first stored
response and replays it byte for byte if the new request's field values hash
to the same value as the one stored, or rejects the call with 409 and
`application/problem+json`, naming the order the key was already used for,
if the body no longer matches:

```java
if (stored.requestHash().equals(hash(request))) {
    return new PlaceOrderResult(stored.statusCode(), stored.responseBody(), null);
}
return new PlaceOrderResult(409, toJson(conflict), null);
```

The hash covers the request's field values, not the raw JSON bytes, so field
order or whitespace in a retried call never causes a false conflict. A
missing key is rejected with 400 before anything is written.

{% include excalidraw.html file="06-idempotency" alt="A retried POST with no idempotency key creates a duplicate order and event; the same retry carrying an Idempotency-Key replays the stored response with a single effect" caption="Figure 6.1: Safety comes from the server's own guard, not caller discipline" %}

Removing the Idempotent Consumer wrapper and routing every call straight to
`placeNewOrder` unconditionally breaks the guarantee directly: two calls
carrying the same key and the same body each run the saga in full, so two
`Order` rows are written and two events reach the topic, and the
same-key-different-body conflict check stops rejecting anything at all,
because nothing is checking the key against a stored response anymore.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must make a retried write safe without asking the caller to coordinate its own retries correctly.
> **Contract shape:** operation intent "place an order, retried safely"; inputs a required idempotency key header plus the request body; response shape an order confirmation, replayed identically on a repeat key; error shape a 409 problem-and-JSON body on a key reused with a different body, a 400 on a missing key; idempotency key: the header itself.
> **Build it here:** "Using `PlaceOrderRoute` and `PlaceOrderSaga` as the model, add the same Idempotent Consumer guard to a second write endpoint on this service, keyed on the same header, replaying on a matching body and rejecting with 409 on a mismatched one."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): a required idempotency key input stored alongside the first response, replayed byte for byte on a repeat key and rejected with a conflict when the same key carries a different payload."
> Check: two requests carrying the same idempotency key and the same body ever produce exactly one underlying write, and a repeat key with a different body is always rejected, never silently accepted.

## Build, run, observe

```bash
./verify.sh
```

Run from the idempotency-safe-retries example, this places one order under a
key, replays the same key and body and confirms the row count and the
response stay unchanged, then reuses the key with a different body and
confirms the call is rejected rather than replayed or re-run. It ends at
18/18.

Try:

- Replay the same `Idempotency-Key` with a different body and inspect the 409
  response: it names the order the key already belongs to.
- Remove the `.idempotentConsumer(...)` wrapper and rerun: a repeat key now
  places a second order instead of being replayed.

## When not to use it

An operation that is already naturally idempotent, a read, or a write that
sets a resource to an absolute state regardless of how many times it is
called, gets nothing from an idempotency key layered on top of it; the key
and its storage are pure overhead there. A key-based guard also needs an
eviction policy of its own: storing every key and its response forever grows
a table without bound, so a production deployment of this pattern needs a
retention window, not shown in this minimal example.

## References

- JJ Geewax, *API Design Patterns*, Manning, 2021
- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- RFC 9457: Problem Details for HTTP APIs
- Apache Camel documentation, Idempotent Consumer: [camel.apache.org/docs](https://camel.apache.org/docs/)
