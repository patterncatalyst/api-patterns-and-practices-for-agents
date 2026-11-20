---
title: "Auditing AI-Using Code"
order: 34
part: "Run in production"
description: "Every outbound call this code's own agent makes leaves an append-only audit record before it goes out, so the calls this code makes are as traceable as the calls made to it."
duration: "20 minutes"
---

## Learning objectives

- Route every outbound call an agent's own code makes through one audited chokepoint, rather than letting a framework make the call invisibly.
- Append an audit record before the call goes out, never after, so no outbound call is ever unaccounted for.
- Prove the audit step actually ran by disabling it and watching the call still succeed with nothing appended.
- Recognize auditing a call's business effect as a different concern from auditing that the call happened at all.

## Why this matters

When an agent's own code calls another team's API, that call leaves this code's boundary with nobody watching from the inside: the team on the other end can see the request arrive, but nothing on this side records that the call was made, what it targeted, or when. A production incident, a cost dispute, or a security review that asks "did our agent call that API, and when" has no answer unless the calling code wrote one down itself. Logging the response after the fact is not enough, since a call can fail to log (a crash, a dropped exception) while the call itself still went out and still had an effect on the other system. The record has to exist before the call can be forgotten, which means it has to be written before the call, not after.

{% include excalidraw.html file="34-audit-before-call" alt="AuditedToolCaller appends an audit record to apa.audit.outbound-calls before calling the remote orderStatus tool; the live projection and the business result both update, and disabling the interceptor skips only the audit step, never the call" caption="Figure 34.1: One chokepoint audits a call before it leaves, never after" %}

## Pattern: Auditing AI-Using Code

### The problem

A call a team's own agent code makes outward, to a tool, a model or another service, can be audited only if every path that makes such a call is forced through the same recording step. Scatter that responsibility across every call site, or leave it to a framework's own internal tool-execution machinery, and a missed call site, or a framework upgrade that changes how it dispatches a call, silently produces an unaudited outbound call with no way to notice until someone asks for a record that was never written.

### The solution

**Every outbound call an agent's own code makes is routed through one chokepoint that appends an audit record to an append-only log before performing the call, so the call and its record can never come apart.**

### How Quarkus and Camel model it

The chokepoint is a single CDI bean, registered as the agent's only local tool rather than left to a framework's own remote-tool auto-execution:

```java
@Tool("Get an order's composed status by calling the remote orderStatus tool. Every call made "
        + "through this tool is audited, on an append-only log, before it goes out.")
public String lookupOrderStatus(String orderId) {
    if (interceptorEnabled) {
        appendAuditRecord(orderId);
    }
    return callRemote(orderId);
}
```

`appendAuditRecord` always runs first, publishing one record (a correlation id, the target, a digest of the argument, and when it happened) to `direct:audit-append-outbound`, which a Camel route hands to Kafka:

```java
from("direct:audit-append-outbound")
        .routeId("audit-append-outbound")
        .setHeader(KafkaConstants.KEY, simple("${body.id}"))
        .marshal(AuditJson.dataFormat())
        .to("kafka:" + AuditTopics.OUTBOUND_CALLS);
```

A second route folds that same append-only log into a live projection, exactly the fold-and-replay discipline an inward audit log uses for calls made in: insert, never update, keyed on the record's own id, so a replay from the beginning of the log always reproduces the live projection and a committed record can only be corrected by appending a new one, never edited in place. Whichever path drives the call, the real model choosing to call this local tool, or a fixture calling it directly with no model involved, both reach the identical chokepoint, so the audit record is appended the same way regardless of which one produced it. The call's business effect, the order status and tracking number the remote tool actually returned, is recorded separately, downstream of the chokepoint: auditing that a call happened and recording what it returned are different concerns, which is what makes it possible to disable the audit step alone and watch the call still succeed.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** every outbound call an agent's own code makes must append an audit record before the call leaves, through one chokepoint every call site shares, never a per-call-site convention.
> **Contract shape:** operation intent "append this outbound call's record, then make the call"; inputs a correlation id, a target name, and a digest of the call's argument, never the raw argument; response shape the call's own result, unaffected by whether the record was appended; error shape a call that still completes even when the audit step is disabled, so the two can be told apart; idempotency key the record's own id.
> **Build it here:** "Using `AuditedToolCaller` as the model, add a second outbound call target this agent can reach, and confirm both targets' calls append to the same log and fold into the same projection."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI: one function every outbound call passes through, which appends an audit record to an append-only store before making the call, with a flag that disables only the append step so a reviewer can confirm the call still runs without it."
> Check: an outbound call made through the chokepoint always has a matching audit record appended before it completed; disabling the audit step leaves the call's own result unchanged but leaves no new record behind.

## Build, run, observe

```bash
./verify.sh
```

Run from the auditing-ai-using-code example, this calls the remote `orderStatus` tool for a seeded order, confirms the audit record and the business result both land, confirms a replay reproduces the live projection and a mutate attempt is rejected, then disables the audit interceptor and confirms the call still succeeds with no new record before restoring it. It ends at 31/31.

Try:

- Disable the audit interceptor and watch the business result still update while the audit count holds steady.
- Read the committed JSON Schema for one audit record and compare it against a folded row's own shape.
- Call the chokepoint for an order ch13 has never seeded and watch the call itself fail before any record is needed.

## Anti-patterns

- **Letting a framework's own remote-tool execution make the call.** If the actual network call happens inside a library's internal dispatch, with application code never in the loop, there is no place left to append a record before it happens.
- **Logging the call's result instead of the call itself.** A record written only after a successful response misses every call that crashed, timed out, or threw before returning, which are exactly the calls most worth having a record of.
- **One audit path per call site.** Duplicating the append-then-call logic at every place an outbound call is made guarantees that one of them eventually skips it, usually the one added last.
- **Auditing and recording the business effect as the same write.** Conflating the two means a bug in the business-effect logic can also silently suppress the audit record, instead of failing independently and visibly.

## When not to use it

A call to a service this same team owns, inside a boundary a shared trace or log already covers end to end, gains little from a second, separate audit record: the existing telemetry already answers who called what and when. Reach for a dedicated outbound-call audit log when the call crosses a boundary this team does not control, where the only record of the call existing at all is the one this code chooses to keep.

## References

- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- Apache Kafka documentation: [kafka.apache.org/documentation](https://kafka.apache.org/documentation/)
- Apicurio Registry documentation: [apicur.io/registry/docs](https://www.apicur.io/registry/docs/)
