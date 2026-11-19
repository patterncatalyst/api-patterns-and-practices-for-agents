---
title: "CI for Agent-Facing APIs"
order: 32
part: "Ship"
description: "Two CI gates, a contract-drift check and a schema-compatibility rule the registry itself enforces, block a merge before an agent ever calls the broken version."
duration: "20 minutes"
---

## Learning objectives

- Wire a contract-drift conformance check and a schema-compatibility check into one pipeline, each failing the build for a different reason.
- Read a schema registry's own rule engine as the compatibility gate, rather than writing a script that tries to guess what counts as a breaking change.
- Confirm a rejection by reading the exception name a registry returns, not just its HTTP status, so an unrelated error can never be mistaken for a correct block.
- Freeze a compatible and a breaking schema candidate from a live, confirmed result, instead of from an assumption about what a schema registry considers breaking.

## Why this matters

A CI pipeline that only runs unit tests on the code calling an API misses the two failures that cost an agent the most: the API itself drifted from what it publishes, or an event producer shipped a payload shape a consumer can no longer parse. Neither failure shows up in a unit test, because a unit test exercises the code that was written, never the contract a different team or a different build relies on. An agent reading a committed OpenAPI document or a committed MCP manifest has no way to ask the service "are you still what you said you were"; it finds out by making a call and getting back something it never expected. An event consumer reading a schema-registered topic finds out the same way, except the bad payload is already on the wire and already read by every other consumer of that topic before anyone notices.

{% include excalidraw.html file="32-ci-for-agent-facing-apis" alt="Two gates block a merge for two different reasons: a contract-drift gate reruns Schemathesis and a manifest diff against the committed OpenAPI and MCP contracts, with a drifted fixture proving it bites, while a schema-compatibility gate posts a candidate event-schema version straight at Apicurio Registry's own BACKWARD compatibility rule, which accepts an optional-field addition and rejects a new required field" caption="Figure 32.1: A contract-drift gate and a schema-compatibility gate catch two different failures, and each names which one fired" %}

## Pattern: CI for Agent-Facing APIs

### The problem

An agent-facing surface ships two kinds of promise at once: a request-response contract (OpenAPI, an MCP tool manifest) a caller builds a client from, and an event schema a producer and a consumer agree on independently of either one's own release schedule. A pipeline that only compiles and unit-tests the service code can pass clean while either promise has already broken: the served contract no longer matches what is committed, or a new schema version a producer wants to ship would stop an existing consumer from reading its own events. Catching either failure after merge means it reaches a running agent before it reaches a reviewer.

### The solution

**A CI pipeline for an agent-facing API runs a contract-drift check that proves a running service still matches its committed contract and manifest, and a schema-compatibility check that lets a schema registry's own rule engine accept or reject a new event-schema version, so neither kind of promise can break without failing the build that would have shipped it.**

### How the harness models it

Gate (a), contract drift, is the conformance check this catalog already built: a pinned Schemathesis container runs the committed OpenAPI contract against the running REST service by its network name, the served document is diffed against the committed one, and the served MCP `tools/list` is diffed against the committed manifest. A committed, drifted copy of each fixture proves the checks discriminate, with no change to either reused service.

Gate (b), schema compatibility, needs no conformance tool at all, because the registry already enforces it. A baseline JSON Schema registers in Apicurio Registry, its artifact carries a `COMPATIBILITY` rule set to `BACKWARD`, and a candidate next version is POSTed straight at the registry:

```bash
curl -X POST "$APICURIO/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT/versions" \
  -H 'Content-Type: application/json' \
  -d "{\"content\":{\"content\":$schema_json,\"contentType\":\"application/json\"}}"
```

The registry's response is the verdict. A version that only adds an optional field returns the new version and an HTTP 200: nothing a reader built against the older schema ever depended on changed. A version that adds a new required field with no default returns an HTTP 400 carrying `"name":"RuleViolationException"` and a cause naming exactly what broke, `"A new required property was added at /required"`: a record written before this version has no value for that field, so a reader compiled against the new schema cannot parse it. No script in this harness decides which of those two outcomes is correct; the registry's own rule engine does, and the harness only reads what it returned.

Reading the exception name, not just the status code, matters because a schema registry can fail to render a verdict at all. Posting a schema declaring a JSON Schema dialect the registry's compatibility checker cannot parse returns its own distinct error, an `UnprocessableSchemaException` reporting that it could not even determine which draft it was looking at, before the compatibility algorithm ever runs. That failure is also a non-2xx response; treating any non-2xx as proof the check correctly rejected a bad schema would let a broken checker pass as a working gate. Confirming the registry's two real outcomes against a live instance, rather than assuming them from documentation, is what caught this: a baseline schema written to the newest JSON Schema draft registered without error, but every later version posted against it failed with that same undetermined-draft error regardless of whether the candidate was compatible or not, because the registry read the dialect from the first version, not the one being checked.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** before a change merges, a running service must be proven to still match its committed contract, and a new event-schema version must be proven backward compatible, with each failure traced to the specific check that caught it.
> **Contract shape:** operation intent "gate a merge on two independent promises"; inputs a committed OpenAPI document, a committed tool manifest, and a candidate event-schema version; response shape a pass or fail per gate, naming which comparison or which rule fired; error shape a contract-drift failure names the diverging field or tool, a schema-compatibility failure names the registry's own rule-violation cause; idempotency key none needed, every check is read-only or additive and safe to repeat.
> **Build it here:** "Using this example's `verify.sh` as the model, add a third candidate schema version that removes an existing field, and confirm against the live registry whether that change is accepted or rejected under `BACKWARD`."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): run a pinned conformance tool against a live service reached by its network name, and let a schema registry's own compatibility rule accept or reject a candidate event-schema version, confirmed against the real registry rather than assumed from its documentation."
> Check: a schema version that only adds an optional field is accepted by the registry, and the same registry rejects a version that adds a required field, with no script in between deciding either outcome.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack brings up the two reused services, their Postgres instances, Apicurio and the rest of the shared infrastructure, then runs the contract-drift gate with its drifted-fixture witness and the schema-compatibility gate with its compatible and breaking candidates, before tearing the stack down. It ends at 21/21.

Try:

- Post the breaking candidate first, before the compatible one, and compare the registry's cause against a baseline with no later version at all.
- Register the baseline with its original JSON Schema dialect and watch every later version fail the same undetermined-draft way, compatible or not.
- Point gate (a) at the drifted OpenAPI contract on its own and watch which specific check fails.

## Anti-patterns

- **Writing a script to decide schema compatibility instead of asking the registry.** A hand-rolled diff reimplements a rule engine the registry already runs correctly, and drifts from it the first time someone forgets to update both.
- **Treating any non-2xx response as a correct rejection.** A registry that cannot parse a schema at all fails with its own distinct error before ever checking compatibility; a gate that cannot tell that error apart from a real rule violation can pass while compatibility checking is silently broken.
- **Assuming a documented compatibility rule from the registry's docs instead of confirming it live.** The confirmed result here, that one JSON Schema dialect produces no verdict at all regardless of content, never appears by reading the rule's description alone.

## When not to use it

A service with no committed contract, or an event schema with no second consumer ever reading a version it did not itself produce, has nothing for either gate to check: contract drift presumes a caller outside the pipeline depends on the committed document, and schema compatibility presumes an existing reader must keep parsing new records. A pipeline still compiling and unit-testing the service gains nothing from adding either gate until a caller or a consumer the team does not control exists to protect.

## References

- Schemathesis documentation: [schemathesis.readthedocs.io](https://schemathesis.readthedocs.io/)
- Apicurio Registry documentation: [apicur.io/registry/docs](https://www.apicur.io/registry/docs/)
- JSON Schema specification: [json-schema.org](https://json-schema.org/specification)
- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
