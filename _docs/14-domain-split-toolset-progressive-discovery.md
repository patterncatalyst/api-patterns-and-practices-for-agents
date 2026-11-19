---
title: "Domain-Split Toolset and Progressive Discovery"
order: 14
part: "Build: MCP packaging"
description: "Splitting a large toolset by domain and discovering it progressively keeps an agent's tool list small enough to pick from accurately."
duration: "25 minutes"
---

## Learning objectives

- Split a wide operation set across named, domain-scoped MCP servers instead of one flat toolset.
- Keep each server's own `tools/list` small, and discover the rest through a shared catalog resource and a search tool.
- Recognize tool inflation and namespace collision as the two failure modes a domain split and a namespaced name both guard against.
- Confirm the framework itself rejects a literal duplicate tool name, rather than relying on a reviewer to catch it.

## Why this matters

A model picks a tool from whatever `tools/list` currently advertises, and picking accuracy degrades as that list grows: more names competing for the same decision means more chances the wrong one gets called, or the right one gets missed entirely. An order-to-delivery domain with twenty plausible operations, place, cancel, refund, reschedule, switch carrier, fetch history, does not need all twenty live on one server to be usable; it needs the handful an agent actually calls kept small, and the rest reachable on request.

{% include excalidraw.html file="14-domain-split-discovery" alt="Two small, domain-namespaced servers keep each tools/list short; a shared catalog resource holds the full twenty-operation set, with searchTools resolving a query to either a live tool or a catalog-only operation" caption="Figure 14.1: Small live toolsets, a shared catalog for the rest" %}

## Pattern: Domain-Split Toolset and Progressive Discovery

### The problem

A single server exposing every plausible operation as a live `@Tool` grows its `tools/list` with every new capability, and MCP's tool namespace is flat: nothing stops two similarly named operations from different domains colliding, and nothing keeps a model's tool-selection accuracy from degrading as the list grows past what it can usefully discriminate between. Treating "expose it as a tool" as the default for every operation a domain could support, rather than the ones an agent actually calls, is what grows the list in the first place.

### The solution

**Two named, domain-scoped servers each advertise only their own small set of live tools, namespaced to avoid colliding on MCP's flat tool namespace, while a shared catalog resource and a search tool let a caller discover and read about the rest of the operation set without it ever becoming a live tool call.**

### How Quarkus models it

`OrderDomainTools` and `ShippingDomainTools` each carry `@McpServer("order")` and `@McpServer("shipping")` respectively, and each keeps exactly two live, namespaced tools:

```java
@ApplicationScoped
@McpServer("order")
public class OrderDomainTools {
    @Tool(name = "order_status_get", description = "Get this order's own lifecycle status, "
            + "customer, item and amount, read from the order schema only.")
    public String orderStatusGet(@ToolArg(description = "The order id, for example O-7001.") String orderId) { ... }

    @Tool(name = "order_cancel_eligibility_get", description = "Get whether this order can still "
            + "be cancelled right now, derived from its current lifecycle status.")
    public String orderCancelEligibilityGet(@ToolArg(description = "The order id.") String orderId) { ... }
}
```

Each named server gets its own Streamable HTTP root path, since `quarkus-mcp-server-http`'s default root path is the literal `/mcp` for every server name with no per-name substitution:

```properties
quarkus.mcp.server.order.http.root-path=/mcp/order
quarkus.mcp.server.shipping.http.root-path=/mcp/shipping
```

`CatalogSearchTools.searchTools`, bound to both server names with the repeatable `@McpServer` annotation, is the one tool identical on both connections: it searches a twenty-entry `OperationCatalog`, ten per domain, and returns a resource link per match rather than inlining the operation's description into the tool-call result. `CatalogResource`, also bound to both names, serves that same catalog as `skill://catalog` (all twenty), a per-domain `skill://{domain}/operations` template (ten each), and a per-operation `skill://{domain}/operations/{operationId}` detail naming, for a live tool's entry, which tool name to call. Sixteen of the twenty catalog entries are never a live `@Tool`: a caller searches, reads the detail, and either calls the named live tool or stops there, having read what it needed without the operation ever appearing in either server's `tools/list`.

A duplicate tool name on one server, two `@Tool` methods both named the same thing, a careless merge might produce by dropping a domain prefix, is not a runtime surprise: Quarkus rejects it during build-time augmentation, before any test or container runs, the same place a duplicate CDI bean or a malformed route would be caught.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must keep an agent's live tool choices few and well separated by domain, while still letting it discover and read about operations that are not live tools.
> **Contract shape:** operation intent "search the operation catalog, then call or read what matched"; inputs a keyword for the search, an operation id for the detail; response shape a resource link per search match, a detail object naming whether an operation is a live tool; idempotency key not needed, every operation here is a read.
> **Build it here:** "Using `OrderDomainTools`, `CatalogSearchTools` and `CatalogResource` as the model, add a third domain server for a new area of the catalog, give it its own namespaced live tools and its own root path, and confirm `searchTools` still resolves catalog entries from all three domains identically from any of the three connections."
> **Build it elsewhere:** "Implement the same pattern in Python: two FastAPI-backed domain services, each fronted by its own small MCP server built with the official Python MCP SDK, plus a shared search tool and catalog resource reachable from either server, so an agent's live tool list per connection stays small while the full operation set stays discoverable."
> Check: each server's own `tools/list` stays small regardless of how large the full operation catalog grows, and merging every operation onto one server, abandoning the split, is the only way to make that stop being true.

## Build, run, observe

```bash
./verify.sh
```

Run from the domain-split-progressive-discovery example, this brings up the stack, negotiates the MCP handshake at each server's own root path, checks each `tools/list` against its own small committed contract, and runs both a catalog-only and a live-tool search-then-fetch round trip. It ends at 21/21.

Try:

- Call `resources/read` on `skill://order/operations` from the shipping connection: the catalog resource is the same bean on both servers, so either connection sees both domains' full operation list even though its live tools stay domain-local.
- Search for a keyword that matches only a catalog-only operation and compare its detail to one that matches a live tool.
- Call `order_cancel_eligibility_get` against a shipped order and a placed order and compare the two results.

## Anti-patterns

- **Tool inflation.** Exposing every plausible operation as a live `@Tool` on one server, growing `tools/list` past what a model can reliably choose from.
- **Flat sprawl across domains.** One merged toolset mixing order and shipping operations without namespacing, so a caller cannot tell which domain a tool name belongs to from the name alone.
- **Namespace collision.** Two operations landing on the same tool name because a merge or a rename dropped the domain prefix; MCP's flat namespace gives the framework no way to tell them apart, and it refuses to build rather than pick one silently.

## When not to use it

A domain split pays for itself once an operation catalog is wide enough that a flat list would hurt tool-selection accuracy; a catalog of three or four operations gains nothing from being split across servers and a search tool, since there is nothing to discover that a short `tools/list` did not already show. Splitting too early also adds a second server, a second root path and a shared catalog bean to maintain for a toolset that was never going to grow past what one server handles cleanly.

## References

- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
- Anthropic, *Writing effective tools for AI agents*: [anthropic.com/engineering/writing-tools-for-agents](https://www.anthropic.com/engineering/writing-tools-for-agents)
- Quarkus MCP Server: [docs.quarkiverse.io/quarkus-mcp-server/dev/index.html](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html)
