# 14 Domain-split toolset and progressive discovery

One Quarkus application hosting two named `@McpServer` domain servers over the shipping domain:
`order` (reading `orders.orders` only) and `shipping` (reading `shipping.shipments` only). Each
server advertises a small, namespaced live tool set: `order_status_get` and
`order_cancel_eligibility_get` on `order`; `shipping_track_get` and `shipping_eta_get` on
`shipping`. Neither server reaches into the other's schema. The full 20-operation
order-to-delivery catalog (10 per domain) exists, but 16 of those 20 operations are never a live
tool: they are named, described and fetchable, never called.

## Two named servers, two endpoints

`quarkus-mcp-server-http` 2.0.4's root-path default is the literal `/mcp` for *every* server name
(`McpHttpServerBuildTimeConfig.Http.rootPath`'s `@WithDefault` carries no server-name
substitution), so two named HTTP servers collide on the same path unless each is given its own:

```properties
quarkus.mcp.server.order.http.root-path=/mcp/order
quarkus.mcp.server.shipping.http.root-path=/mcp/shipping
```

Confirmed live in the startup log:

```
MCP [order] HTTP transport endpoints [streamable: http://localhost:8081/mcp/order, ...]
MCP [shipping] HTTP transport endpoints [streamable: http://localhost:8081/mcp/shipping, ...]
```

A bean (or method) joins a named server with the repeatable `@McpServer("name")` annotation;
`OrderDomainTools` carries `@McpServer("order")`, `ShippingDomainTools` carries
`@McpServer("shipping")`, and the two shared beans (`CatalogSearchTools`, `CatalogResource`) carry
both annotations, so they appear identically on both connections.

## Progressive discovery instead of a big live toolset

`CatalogSearchTools.searchTools(query)` is the one tool identical on both servers: it searches
the full `OperationCatalog` (20 entries, both domains) and returns a `ResourceLink` per match,
never the operation's full description inline. `CatalogResource` serves the same catalog as
resources: a static `skill://catalog` (all 20), a `@ResourceTemplate` `skill://{domain}/operations`
(10 per domain), and a `@ResourceTemplate` `skill://{domain}/operations/{operationId}` (one
operation's detail, including `howToInvoke`, which names the live tool to call for a `"tool"`-kind
entry and says the operation is catalog-only otherwise).

A caller discovers an operation by searching, reads its detail, and either calls the live tool
`tools/call` names or stops there:

- `searchTools("refund")` matches `order_refund_post` only; its detail reads `"kind": "catalog"`,
  since refunding a cancelled order's payment is not a live tool in this example.
- `searchTools("track")` matches `shipping_track_get` only; its detail reads
  `"kind": "tool", "liveToolName": "shipping_track_get"`, and calling that tool returns the
  seeded shipment's real carrier and tracking number.

## The negative witness

The point of the split is that each server's `tools/list` stays small. To confirm the small size
is enforced, not incidental, a `MergedCatalogTools` class was added by hand: `@McpServer("order")`
only, exposing all 16 catalog-only operations as live `@Tool` stub methods, merging both domains'
full operation sets onto the `order` server and abandoning the split.

Two failure modes showed up, both informative:

1. **A literal name collision** (two `@Tool` methods both named `history_get`, one meant for
   `order_history_get` and one for `shipping_history_get`, simulating a careless merge that drops
   namespacing) is a **build-time failure**: `quarkus-mcp-server-core-deployment` 2.0.4 throws
   `java.lang.IllegalStateException: Duplicate feature method found for history_get` from
   `McpServerProcessor.checkOverlappingServers`, and `mvn -B verify` never gets past augmentation.
2. With the collision renamed away (both tools kept, distinctly named), the build succeeds but the
   split is gone: `mvn -B verify` fails 2 of 12 tests
   (`orderServerInitializesAndAdvertisesOnlyItsOwnSmallToolSet`, expecting 3 tools and getting 19;
   `namespacedToolNamesDoNotCollideAcrossServers`, since `shipping_`-prefixed names now live on the
   `order` server). A live `./verify.sh` run falls to 19/21: check 9 (`order` server's
   `tools/list` count `<= 4`) fails at a live count of 19, and check 10 (the committed
   `contracts/14-order-tools.mcp.json` diff) fails with it. Both the `mvn -B verify` and the live
   `./verify.sh` degradation are recorded in `_plans/verification-log.md`.

`MergedCatalogTools.java` was removed afterward; both `mvn -B verify` and `./verify.sh` were
reverified green (12/12, 21/21), byte-identical to the committed example.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack comes up, both servers negotiate protocol revision `2025-11-25` at their own root
paths, each `tools/list` is checked against its committed `contracts/14-*-tools.mcp.json` and
against the `<= 4` degradation threshold, the shared `skill://catalog` resource and its two
`@ResourceTemplate`s are checked against `contracts/14-catalog.mcp.json`, and both search-then-fetch
round trips run, before the stack is torn down. Set `KEEP=1` to leave the stack running.

- Try `resources/read` on `skill://order/operations` from the `shipping` connection (and vice
  versa): the catalog resource is the same bean on both servers, so either connection sees both
  domains' full operation list even though its live tools stay domain-local.
- Try `searchTools("eta")` and compare the `ResourceLink` it returns to calling
  `shipping_eta_get` directly.
- Try `order_cancel_eligibility_get` against the seeded `O-7001` (SHIPPED, not cancellable) and
  `O-7002` (PLACED, cancellable) and compare the two results.
