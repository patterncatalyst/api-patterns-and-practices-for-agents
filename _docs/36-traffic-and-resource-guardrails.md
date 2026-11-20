---
title: "Traffic and Resource Guardrails"
order: 36
part: "Run in production"
description: "An agent-facing ingest endpoint caps its call rate and rejects a payload that fails its registered schema, both enforced by the framework and the registry, never by convention."
duration: "20 minutes"
---

## Learning objectives

- Cap an agent-facing endpoint's call rate with a framework-native rate limit, not a hand-rolled counter.
- Map a rate-limit rejection to a 429 problem response that still leaves a durable, auditable record behind.
- Gate an ingest endpoint on a registered JSON Schema, so a malformed payload never reaches the logic it was meant to protect.
- Disable each guardrail independently and confirm the other still rejects, proving neither depends on the other to work.

## Why this matters

When an agent-facing ingest endpoint has no cap on how often it can be called, a caller that retries aggressively, a misconfigured automation loop, or an attacker's script can all drive the same unbounded volume of calls, and the service has no way to tell them apart from legitimate traffic until something downstream already failed. When the same endpoint has no check on the shape of what it accepts, a payload an agent assembled from a bad prompt, a stale schema version, or a straightforward bug reaches the code meant to act on it, and the first thing to notice the problem is whatever that code does next. Both failures share one root cause: the endpoint trusted the caller to behave, instead of enforcing a limit the framework can hold even when the caller does not.

{% include excalidraw.html file="36-ingest-guardrails" alt="An ingest call passes through a rate limit, then a schema gate; either one can reject the call with a problem+json response and a durable rejection record before the other ever runs" caption="Figure 36.1: Either guardrail can reject the call before the other ever runs" %}

## Pattern: Traffic and Resource Guardrails

### The problem

An endpoint that accepts every call at whatever rate it arrives, and parses whatever body it receives before checking its shape, has no floor under either axis of abuse: volume and validity. Adding a rate check or a shape check as an afterthought inside the business logic puts the guard exactly where a caller already consumed the resources it was meant to protect: a database connection, a downstream call, a thread.

### The solution

**An ingest endpoint caps its call rate with a framework-enforced interceptor that runs before the method body, and validates every accepted call's payload against a registered schema before any domain logic ever sees it, so both guardrails reject at the door rather than partway through.**

### How Quarkus models it

The rate limit is one annotation from `quarkus-smallrye-fault-tolerance`, a SmallRye addition beyond the MicroProfile Fault Tolerance specification:

```java
@POST
@Path("/orders")
@RateLimit(value = 5, window = 30, windowUnit = ChronoUnit.SECONDS)
public Response ingestOrder(String rawBody) {
    ...
}
```

`@RateLimit` is a CDI interceptor: it runs before `ingestOrder` ever executes, keyed by bean class and method, not by caller, so every call against this one endpoint counts against the same shared budget. A call over the limit throws `io.smallrye.faulttolerance.api.RateLimitException`, which by default surfaces as a bare HTTP 500; a `@ServerExceptionMapper` maps it to a 429 instead:

```java
@ServerExceptionMapper
public RestResponse<RateLimitProblem> mapRateLimited(RateLimitException e) {
    rejectionRecorder.record("rate_limit");
    return RestResponse.ResponseBuilder.create(RestResponse.Status.TOO_MANY_REQUESTS, RateLimitProblem.of())
            .type("application/problem+json")
            .build();
}
```

A call that clears the rate limit is parsed and checked against a schema loaded once at startup from a committed, draft-07 JSON Schema file, using `com.networknt:json-schema-validator`:

```java
public List<String> validate(JsonNode payload) {
    if (!enabled) {
        return List.of();
    }
    return schema.validate(payload).stream()
            .map(violation -> violation.getInstanceLocation() + ": " + violation.getMessage())
            .toList();
}
```

A non-empty violation list throws before an `IngestOrder` is ever constructed, and the same exception-mapping discipline turns it into a 400. Both rejection paths call the same recorder, so a rejection for either reason leaves a durable row and increments a Micrometer counter tagged by reason, not only a transient status code a client happened to see once.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** an ingest endpoint must cap how often it can be called and reject a payload that fails a published schema, both before any domain logic runs, and both rejections must leave a record a caller cannot erase by retrying.
> **Contract shape:** operation intent "ingest"; inputs a JSON payload matching a published schema; response shape an acceptance acknowledgment or a problem response; error shape a rate-limit problem distinct from a schema-violation problem, each naming what the caller can do next; idempotency key not required, since a rejection has no side effect to replay.
> **Build it here:** "Using `IngestResource.ingestOrder` as the model, add a second rate-limited endpoint with its own, independent limit, and confirm exhausting one endpoint's budget never affects the other's."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): an ingest endpoint behind a request-rate limiter and a JSON Schema validation step, each rejecting with a distinct, machine-readable error shape and each incrementing its own durable rejection counter."
> Check: a caller over the rate limit is rejected regardless of payload validity, a caller within the limit but with an invalid payload is rejected regardless of rate, and only a caller that is both within the limit and carrying a valid payload is ever accepted.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack comes up with the ingest service, Apicurio and the observability stack. The committed schema registers in Apicurio; a valid payload is accepted; an invalid one is rejected with its violations named; a burst past the rate limit is rejected on its sixth call; both rejections leave a durable row and a Prometheus metric. The rate limit is then overridden to a no-op and the schema gate is disabled, one at a time, each time confirming the other guardrail still rejects, before both are restored. It ends at 23/23.

Try:

- Send a burst of valid payloads and watch the rejection count grow only after the limit is reached, never before.
- Omit a required field from the payload and read which field the 400 response names.
- Disable the schema gate alone and confirm the rate limit still rejects a burst on its own.

## Anti-patterns

- **Checking shape after acting on it.** Deserializing a payload into a domain object before validating it against a schema means a malformed call has already consumed the deserialization cost, and any side effect a constructor or setter triggers, before the service decides to reject it.
- **A rate limit with no durable trace.** A rate limit that only returns a different status code, with nothing recorded anywhere, gives an operator no way to tell a brief spike from a sustained attack after the fact.
- **One combined toggle for two guardrails.** A single flag that disables both the rate limit and the schema gate together hides which guardrail actually caught a given rejection, and makes it impossible to prove either one works independently of the other.

## When not to use it

An internal endpoint called only by a small, trusted set of services behind its own network boundary, with a shape already enforced by a shared client library, gains little from an application-level rate limit and schema gate: the cost of the extra interceptor and the extra validation pass buys protection against a caller that structurally cannot misbehave. Reach for both guardrails on any endpoint an agent, a script, or an external party can call directly, where neither the caller's good behavior nor its payload's shape is ever guaranteed.

## References

- SmallRye Fault Tolerance documentation: [smallrye.io/docs/smallrye-fault-tolerance](https://smallrye.io/docs/smallrye-fault-tolerance/)
- RFC 9457: Problem Details for HTTP APIs: [rfc-editor.org/rfc/rfc9457](https://www.rfc-editor.org/rfc/rfc9457.html)
- JSON Schema specification: [json-schema.org](https://json-schema.org/specification)
- Apache Kafka documentation, Authorization and ACLs: [kafka.apache.org/documentation](https://kafka.apache.org/documentation/#security_authz)
- Apache Kafka documentation, Quotas: [kafka.apache.org/documentation](https://kafka.apache.org/documentation/#design_quotas)
- Apicurio Registry documentation: [apicur.io/registry/docs](https://www.apicur.io/registry/docs/)
- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
