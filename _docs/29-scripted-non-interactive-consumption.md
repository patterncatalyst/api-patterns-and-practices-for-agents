---
title: "Scripted Non-Interactive Consumption"
order: 29
part: "Test and automate"
description: "A committed collection drives the same tool contract a human would exercise by hand, scripted and repeatable, and the run is judged by the runner's structured report, never by its exit code."
duration: "20 minutes"
---

## Learning objectives

- Drive an agent-facing tool contract without a human or a browser, scripted and repeatable from a clean stack.
- Reuse a strict self-describing REST service unchanged, exercising the same operations a person would run by hand.
- Assert on a pinned runner's structured report, its pass and fail counts and a named error-path assertion, instead of reading its process exit code.
- Recognize an exit-code assertion, a forked copy of the target, and an unpinned runner as the ways a scripted drive stops proving anything.

## Why this matters

A tool contract that a person has clicked through once in a browser is proven once, by someone who is no longer watching. The same drive has to run on every change, with no one at the keyboard, and it has to fail loudly when the contract drifts. The trap is the runner's exit code. A test harness that trusts the exit code is trusting the runner to have noticed every deviation and to have signalled it the way the harness expects, and a drifted error path can slip through that gap. An unknown order answered as a 500 with a plain-text body instead of a 404 with a problem document is a real regression a consuming agent would choke on, and it is exactly the kind of drift an exit-code check can miss while still reporting success.

{% include excalidraw.html file="29-scripted-non-interactive" alt="An exit-code-judged run passes whether or not the contract held, while a pinned Newman container drives the committed collection and the run is judged by the JSON report's assertion counts and the named unknown-order 404 problem+json assertion" caption="Figure 29.1: The report, not the exit code, is what the checks read" %}

## Pattern: Scripted Non-Interactive Consumption

### The problem

An agent-facing contract validated by hand is validated once, and a drive validated only by a runner's exit code can report success while an error path has regressed. The exit code collapses every assertion into one bit, decided by the runner, and that one bit cannot distinguish a clean run from a run where the status code held but the content type and body shape did not.

### The solution

**A committed collection drives the real tool contract through a pinned one-shot runner on the service network, and the run is judged by the runner's structured report, its pass and fail counts and a named error-path assertion, not by its process exit code.**

### How the scripted run models it

The subject is a strict self-describing REST service, reused unchanged: the harness builds the service's own image from its own build context through compose and never copies or forks its code. It owns no application of its own. What it owns is a committed Postman collection and a harness around a pinned runner.

The collection walks the four moves a consumer makes against the two operations, asserting the status code, the content type and the body shape on each: place an order and get a 201 with a non-empty order id, read that order back and get a 200 with the same id and a PLACED status, ask for an order that does not exist and get a 404 with an `application/problem+json` body, and submit an order with a quantity of zero and get a 400 with a problem document. Newman, a pinned and maintained open source collection runner, runs as a one-shot container on the application network and reaches the service by compose service DNS, never through the host port.

The run is judged by the report, not the exit code. Newman writes a JSON report to a host-mounted directory, and the checks read it with `jq`:

```bash
every_assertion_passed() {
    local failed total
    failed=$(jq -r '.run.stats.assertions.failed // 1' "$REPORT")
    total=$(jq -r '.run.stats.assertions.total // 0' "$REPORT")
    [[ "$total" -gt 0 && "$failed" == "0" ]]
}
```

The runner's own exit code is left unread. The named unknown-order assertion is the biting negative witness, read straight from the report:

```bash
unknown_order_404_assertion_held() {
    jq -e --arg name "$UNKNOWN_ORDER_REQUEST" '
        [ .run.executions[] | select(.item.name == $name) | .assertions[]? ] as $a
        | ($a | length) > 0 and (all($a[]; .error == null))
    ' "$REPORT" >/dev/null
}
```

When the service's error handling drifts, its exception mappers removed so an unknown order falls through to a 500 with a `text/plain` body, the status and content-type assertions on that request gain an error in the report, and this check turns from pass to fail. The drift is observable from the report alone, with the runner's exit code never consulted.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must be drivable with no human and no browser, so the same operations a person would exercise by hand run scripted and repeatable on every change.
> **Contract shape:** operation intent "place an order, read it back, ask for one that does not exist, submit an invalid one"; inputs a JSON order body and an order id in the path; response shape a created order as `application/json` and a `Problem` as `application/problem+json` on the error paths; error shape a 404 for an unknown order and a 400 for an invalid one, each a problem document with title, status and detail; idempotency key none needed, the drive reads back what it placed.
> **Build it here:** "Using this example's committed Postman collection as the model, add a request that reads back the order placed by the first request and asserts its status is PLACED, then confirm from the JSON report that every request ran its assertions."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): a committed collection or script that drives the create, read, unknown and invalid paths and is judged by a structured report, not by the runner's exit code."
> Check: an unknown order answered as a 500 instead of a 404 problem+json turns the report's named 404 assertion into a failure, dropping the run below a clean pass.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack comes up with the order service and the shared infrastructure, the pinned runner drives the committed collection from a one-shot container on the application network, the checks read the JSON report, and the stack is torn down. It ends at 11/11.

Try:

- Set `KEEP=1` and run the committed collection by hand against the published host port with any local Newman or from Postman.
- Open the collection and read the four requests and their assertions: status, content type and body shape on each.
- Rebuild the target with its error mappers removed and rerun: the named 404 problem+json assertion fails and the run drops to 9/11.

## Anti-patterns

- **Judging the drive by the runner's exit code.** One bit decided by the runner cannot tell a clean run from one where the status held but the content type and body shape drifted; a regressed error path can pass an exit-code check while failing a named assertion in the report.
- **Forking the target into the harness.** A copy of the service under test drifts from the real one, so the drive proves a stale contract; building the service's own image from its own context keeps the drive pointed at the real contract.
- **An unpinned runner.** A runner pulled as a floating tag can change behaviour between runs, so a passing drive today and a failing one tomorrow say nothing about the contract; pin the runner by tag and digest.

## When not to use it

An operation whose real behaviour only shows up later, as a durable row or a published event long after the call returned, is not captured by a drive that reads an immediate response. A scripted request-and-assert proves the synchronous contract, and an asynchronous effect needs a harness that observes the effect under a bounded poll instead. A contract still being shaped, where the response format changes every day, also gets little from a committed collection that would have to be rewritten each time.

## References

- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- Postman collection format documentation: [learning.postman.com/collection-format](https://learning.postman.com/collection-format/)
- Newman documentation: [github.com/postmanlabs/newman](https://github.com/postmanlabs/newman)
- RFC 9457, Problem Details for HTTP APIs: [rfc-editor.org/rfc/rfc9457](https://www.rfc-editor.org/rfc/rfc9457)
