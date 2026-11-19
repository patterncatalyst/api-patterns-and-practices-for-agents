---
title: "Contract and Manifest Conformance"
order: 27
part: "Test and automate"
description: "A scripts-only harness checks that a running service still matches its published contract and tool manifest, not merely that it starts."
duration: "20 minutes"
---

## Learning objectives

- Check a running service against its published contract, not just its health probe.
- Run a pinned, maintained conformance tool against a live service over its real network, by service name, not a mocked client.
- Diff a served document against a committed one so drift between what a service says and what it does becomes a failing check.
- Prove a conformance check discriminates by running the same checks against a committed drifted contract and a committed drifted manifest, and watching both fail.

## Why this matters

A service that boots and answers its readiness probe can still have drifted away from the contract it publishes: a field tightened, an error response quietly dropped, a tool renamed in the manifest that callers already generated code against. None of that shows up in a health check, because a health check only asks whether the process is up, never whether what it serves still matches what it promised. An agent that builds a request from a committed OpenAPI document, or chooses a tool from a committed MCP manifest, is trusting that document to be true right now, not just true the day it was written.

{% include excalidraw.html file="27-contract-manifest-conformance" alt="A readiness probe confirms the process answers; a conformance check runs the committed OpenAPI contract through Schemathesis against the running service by its network name, diffs the served document against the committed one, and diffs the served MCP tools list against the committed manifest, with a drifted copy of each fixture proving the checks actually fail when the contract disagrees with the service" caption="Figure 27.1: A conformance check compares what a service actually serves against what it commits to, and a drifted fixture proves the comparison bites" %}

## Pattern: Contract and Manifest Conformance

### The problem

Readiness checks a process starting, nothing else; they never read a line of the contract that process is supposed to honor. A field can be renamed, a response schema tightened, a tool dropped from a manifest, and every readiness probe in the stack keeps reporting green, because none of them ever looks at the contract at all. By the time an agent built against the published contract hits a response it was never told to expect, the mismatch has already reached production.

### The solution

**A running service is checked against its own published contract and tool manifest, using a pinned conformance tool that generates requests from the contract and a document diff that compares what is served against what is committed, and the check is proven to bite by running it against a known-drifted copy of each.**

### How the conformance check models it

The harness is scripts only: no application of its own, no new service to maintain. It brings up a strict self-describing REST service and a packaged MCP tool server, both reused unchanged from their own images, and runs four checks against them over the network.

The first check runs a pinned, maintained open-source conformance tool, Schemathesis, as a one-shot container on the same Docker network as the service under test, reaching it by its compose service name rather than a host port:

```bash
docker run --rm --network apa -v "<repo>/contracts:/contracts:ro" \
  schemathesis/schemathesis:4.33.3 run /contracts/order-service.openapi.yaml \
  -u http://strict-self-describing-contract:8080 \
  -c status_code_conformance,content_type_conformance,response_schema_conformance
```

Schemathesis reads the committed OpenAPI document, generates requests from it, and asserts the service's actual status codes, content types and response bodies all conform to what the document describes. The signal the harness reads is whether Schemathesis exits clean: no generated request is allowed to land outside what the contract says can happen.

The second check fetches the service's own served OpenAPI document and normalizes it, along with the committed contract, to sorted-key JSON so that key order and JSON-versus-YAML formatting never produce a false mismatch, then diffs the two. A service can conform to every status code and schema Schemathesis checks and still have published a document that no longer matches the one a consumer generated a client from; this check catches that drift directly.

The third and fourth checks cover the MCP side: the harness drives the packaged tool server's MCP lifecycle over Streamable HTTP, lists its tools, and asserts the served tool set is identical to the committed manifest.

None of these four checks mean anything unless they can fail. The harness proves it with a biting negative witness: a committed, drifted copy of the OpenAPI contract and a committed, drifted copy of the MCP manifest. The drifted OpenAPI document tightens a response schema with a field the service never actually returns and removes two of its documented error responses; the drifted manifest renames a tool and drops a required parameter from another. The harness runs the exact same Schemathesis conformance and the exact same tools-list diff against these drifted fixtures and asserts both runs fail. No source in either reused service changes to produce this failure; only the committed fixture the check is pointed at changes.

Conformance checking a running service, not just its startup, is what caught three real gaps during this harness's own build: an out-of-range value, a malformed request body, and a missing required field each produced a response the contract never described, every one escaping as an undocumented 500 that a health probe would have called healthy the entire time.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must prove, with a running check and not a human reading two documents side by side, that a service's actual behavior still matches the contract and tool manifest it publishes.
> **Contract shape:** operation intent "verify a running service against its published description"; inputs a committed OpenAPI document and a committed tool manifest, reached over the network by service name; response shape a pass or fail per check, naming which comparison failed; error shape a conformance failure names the specific status code, schema field or tool definition that diverged, never a generic mismatch; idempotency key none needed, the check is read-only and safe to repeat.
> **Build it here:** "Using this example's `verify.sh` as the model, add a drifted fixture for a response the service already serves and prove the same Schemathesis run rejects it."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): run a pinned conformance tool against a live service reached by its network name using its committed OpenAPI or schema document, diff the served document against the committed one, and prove both checks fail against a known-drifted copy of the contract."
> Check: the same conformance run that passes against the real contract fails against a drifted copy of it, with no change to the service under test.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack brings up both reused services plus the shared infrastructure, runs the four conformance checks against the real contracts, then runs the same two checks against the committed drifted fixtures and confirms they fail, before tearing the stack down. It ends at 14/14.

Try:

- Point the Schemathesis run at the drifted OpenAPI contract and watch the undocumented status codes turn up as failures.
- Diff the served tools list against the drifted MCP manifest and see the renamed tool.
- Edit a committed contract to disagree with the running service and watch the matching check fail.

## Anti-patterns

- **Trusting a readiness probe as a conformance signal.** A process that starts and answers health checks has told you nothing about whether its responses still match its contract.
- **Testing the contract document, not the running service.** Validating that an OpenAPI file is well-formed YAML proves nothing about whether the service actually behaves the way that file claims.
- **A conformance check with no negative witness.** A check that has never been seen to fail against a known-bad contract might be passing for the wrong reason, or not running at all.

## When not to use it

A service with no published contract, an internal implementation detail no caller consumes directly, gets nothing from this pattern: conformance checking exists to keep a promise a contract makes to someone else, and there is no promise to check when nobody outside the service reads its shape. A contract that changes on every commit without any consumer pinned to a version also fights the pattern: conformance checking assumes the contract is the stable side of the relationship, and a tool that regenerates and reasserts everything each run is checking its own tail.

## References

- Schemathesis documentation: [schemathesis.readthedocs.io](https://schemathesis.readthedocs.io/)
- OpenAPI Specification, version 3.1: [spec.openapis.org](https://spec.openapis.org/oas/v3.1.0)
- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
