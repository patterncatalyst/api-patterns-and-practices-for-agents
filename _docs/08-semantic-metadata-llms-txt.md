---
title: "Semantic Metadata and llms.txt + Dual-Use Portal"
order: 8
part: "Build: synchronous APIs"
description: "A machine-readable llms.txt and a shared portal serve a browsing human and a reading agent from the same source, instead of two surfaces drifting apart."
duration: "20 minutes"
---

A human lands on an API's documentation portal; a model reads `/llms.txt`
instead, a plain-text summary written for a reader that parses text rather
than clicking links. Writing each surface from its own hand-maintained copy
of the operation list looks harmless on day one, when the two copies agree
by construction, and stops being harmless the first time either surface adds
an operation the other one does not get told about.

## Pattern: Semantic Metadata and the Dual-Use Portal

### The problem

A human portal and a machine-readable `llms.txt`, each written and
maintained as its own independent document, name the same API from two
separate sources of truth. The question is never whether they will drift,
only when: an operation added to one write-up and forgotten in the other
leaves an agent reading `llms.txt` with either a tool that does not exist on
the portal, or a tool the portal lists that the agent never learns about.
Keeping the two in sync becomes a discipline problem, something a developer
has to remember on every change, rather than something the system enforces.

### The solution

**One metadata source renders both the machine-readable surface and the
human portal, so the two can never describe a different set of operations
because there is only one description to read from.**

### How Quarkus and Camel model it

`ApiMetadata` is the single CDI bean that owns every operation's id, HTTP
method, path and summary:

```java
@ApplicationScoped
public class ApiMetadata {
    public String title() { return "Order Service"; }
    public String summary() { return "Agent-facing order service: place an order and look one up by id."; }
    public List<OperationInfo> operations() {
        return List.of(
                new OperationInfo("placeOrder", "POST", "/orders", "Place a new order."),
                new OperationInfo("getOrder", "GET", "/orders/{id}", "Look up an order by id."));
    }
}
```

`LlmsTxtResource` renders `/llms.txt` as `text/plain`, in the llmstxt.org
shape (an H1 title, a blockquote summary, then `##` sections with markdown
links), by looping over `metadata.operations()`:

```java
out.append("# ").append(metadata.title()).append("\n\n");
out.append("> ").append(metadata.summary()).append("\n\n");
out.append("## Operations\n\n");
for (OperationInfo op : metadata.operations()) {
    out.append("- [").append(op.operationId()).append("](/q/openapi#/operations/")
            .append(op.operationId()).append("): ").append(op.method()).append(' ').append(op.path())
            .append(", ").append(op.summary()).append('\n');
}
```

`PortalResource` renders `/portal` as `text/html` from a Qute template,
handing the same three values, title, summary and `operations()`, to the
template's data model. Neither resource keeps a second copy of the
operation list; both read it fresh from `ApiMetadata` on every request.
`placeOrder` and `getOrder` are served the usual way, Quarkus REST with
Camel publishing `OrderPlaced` on the event edge, so the metadata layer sits
beside the same order-service surface every other build/sync example ships.

{% include excalidraw.html file="08-dual-use-portal" alt="Two independently maintained write-ups of the same API drift apart over time; one shared metadata bean rendering both the plain-text and HTML surfaces keeps them naming exactly the same operations" caption="Figure 8.1: One source, two renderings, never two sources" %}

The guarantee is a set-equality check: the operation ids a reader can extract
from `/llms.txt`'s markdown links and the ids a reader can extract from the
portal's rendered operations must be exactly the same set. Bypassing
`ApiMetadata` for a single hardcoded line, appending one extra operation
directly in `LlmsTxtResource` without touching `PortalResource`, breaks that
set equality immediately: `/llms.txt` then names an operation the portal
never mentions, reproducing in miniature the exact drift the shared bean
exists to prevent.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must describe the same set of operations to a human browsing a portal and a model reading a plain-text summary, from one source, so the two can never list a different set of operations.
> **Contract shape:** operation intent "publish machine-readable and human-readable descriptions of the same operations from one source"; inputs none, both are reads; response shape a plain-text summary in a stable, parseable shape for the machine surface, and an HTML page for the human one; error shape not applicable, both are always-available reads; no idempotency key needed.
> **Build it here:** "Using `ApiMetadata` and `LlmsTxtResource` as the model, add a third operation's metadata to the shared bean and confirm both `/llms.txt` and `/portal` name it without editing either resource directly."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): render a plain-text, machine-readable operation summary and a human-facing page from one shared metadata source, so the two surfaces can never name a different set of operations."
> Check: the set of operation ids extracted from the machine-readable surface and the set extracted from the human surface are always equal, for any operation added to the shared source.

## Build, run, observe

```bash
./verify.sh
```

Run from the semantic-metadata-llms-txt example, this brings up the
service, places and reads back an order, fetches `/llms.txt` and `/portal`,
confirms the content types and the llmstxt.org shape, and diffs the served
`/llms.txt` against the committed contract. It ends at 17/17.

Try:

- Append a hardcoded operation line to `LlmsTxtResource.llmsTxt()` that
  bypasses `ApiMetadata` and rerun: the set-equality check fails because the
  portal never learns about the extra operation.
- Add a real operation to `ApiMetadata.operations()` and rerun: both
  surfaces pick it up without either resource changing.

## When not to use it

A single-operation or rarely-changing API gets little from a shared metadata
bean beyond what careful copy-editing already gives it; the coordination
cost this pattern removes only shows up once an API has enough operations,
or changes often enough, that a human keeping two write-ups in sync actually
starts to fail. A portal that shows more than a model should read, internal
operations, deprecated ones kept for reference, needs the two surfaces to
differ by design, which calls for a filtered view over the shared source
rather than one unfiltered rendering of it.

## References

- `llms.txt`: [llmstxt.org](https://llmstxt.org/)
- Leonard Richardson, Mike Amundsen, Sam Ruby, *RESTful Web APIs*, O'Reilly, 2013
- Quarkus Qute templating guide: [quarkus.io/guides/qute](https://quarkus.io/guides/qute)
- Apache Camel documentation: [camel.apache.org/docs](https://camel.apache.org/docs/)
