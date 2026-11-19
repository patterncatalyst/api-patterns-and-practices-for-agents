---
title: "MCP-First Single CDI Bean"
order: 12
part: "Build: synchronous APIs"
description: "One CDI bean serves REST, GraphQL and an MCP tool from the same business logic, so the three surfaces never drift apart."
duration: "25 minutes"
---

## Learning objectives

- Hold the business logic in one CDI bean instead of reimplementing it per transport.
- Delegate REST, GraphQL and the MCP `@Tool` to that bean unchanged.
- Confirm cross-face equality: the same input returns the same result regardless of transport.
- Disable stdio for a detached-container profile while keeping it enabled for an attached process.

## Why this matters

A REST endpoint, a GraphQL query and an MCP tool that each reimplement the
same read independently are three places the same business rule has to be
kept correct, and three places it can quietly stop being the same rule. The
moment one implementation is touched and the other two are not, nothing
forces them to agree, and nothing tells a caller they have stopped agreeing
until the results no longer match.

## Pattern: MCP-First Single CDI Bean

### The problem

Three faces over one piece of business logic, a REST resource, a GraphQL
resolver and an MCP tool, each implementing the read on its own, give an
agent no guarantee that calling the same operation through a different
transport returns the same answer. A fix applied to one face and forgotten
in the other two is not a hypothetical risk, it is the default outcome of
maintaining the same logic three times, and nothing about having three
faces makes that drift visible until a caller compares their results
directly.

### The solution

**One CDI bean holds the business logic once, and every transport, REST,
GraphQL and an MCP tool, delegates to it unchanged, so the same input always
returns the same result regardless of which face a caller used.**

### How Quarkus models it

`OrderOperations` is the one bean, with one method, that actually reads an
order's summary:

```java
@ApplicationScoped
public class OrderOperations {
    @Inject DataSource dataSource;

    public OrderSummary orderSummary(String orderId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT id, customer_id, item_sku, quantity, amount, status "
                                + "FROM orders.orders WHERE id = ?")) {
            select.setString(1, orderId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) throw new OrderNotFoundException(orderId);
                return new OrderSummary(rs.getString("id"), rs.getString("customer_id"),
                        rs.getString("item_sku"), rs.getInt("quantity"), rs.getBigDecimal("amount"),
                        rs.getString("status"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("order summary lookup failed for " + orderId, e);
        }
    }
}
```

The REST face and the GraphQL face each call that one method and return
whatever it returns, nothing more:

```java
// REST
public OrderSummary summary(@PathParam("id") String id) { return ops.orderSummary(id); }

// GraphQL
@Query("orderSummary")
public OrderSummary orderSummary(@Name("orderId") String orderId) { return ops.orderSummary(orderId); }
```

The MCP face, `OrderMcpTools`, is a single `@Tool` served over Streamable
HTTP by the MCP server extension, and it delegates the same way, serializing
the same `OrderSummary` instance the other two faces return:

```java
@Tool(description = "Get the lean order summary for an order id. Returns the same summary "
        + "the REST GET /orders/{id}/summary endpoint and the GraphQL orderSummary query return.")
public String orderSummary(@ToolArg(description = "The order id, for example O-9001.") String orderId) {
    return objectMapper.writeValueAsString(ops.orderSummary(orderId));
}
```

An `initialize` handshake against the tool endpoint negotiates MCP protocol
revision 2025-11-25, and `tools/list` advertises `orderSummary` with an
input schema naming one required string argument, `orderId`. This example
has nothing to write, so there is no Camel route: a read has no event to
publish, and the MCP tool reads the same way the REST and GraphQL faces do.

{% include excalidraw.html file="12-mcp-first-bean" alt="Three faces reimplementing the same read independently can drift from each other silently; three faces delegating to one shared CDI bean return the same result by construction, because there is only one implementation to call" caption="Figure 12.1: One bean behind three transports never drifts, because it never duplicates" %}

Diverging one face from the shared bean, changing the GraphQL resolver to
stop delegating and return a different value for one field instead, breaks
cross-face equality directly: the diverged face still runs and still
returns a result for the right order id, so a check on that face alone would
still pass, but a check comparing all three faces against each other fails,
because the diverged face no longer agrees with the other two. One
operational note worth carrying forward: the MCP stdio transport reads
standard input, so a container started detached has no stdin to read and
shuts the application down within seconds unless stdio is disabled for that
runtime profile; stdio stays enabled for a direct, attached process, which
is how a stdio-based client consumes the same tool.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must return the same business result for the same input regardless of which transport, REST, GraphQL or an MCP tool, a caller used to reach it.
> **Contract shape:** operation intent "serve one read over three transports from one implementation"; inputs an order id; response shape the same lean summary on every face; error shape the same not-found behavior on every face; no idempotency key needed, this is a read.
> **Build it here:** "Using `OrderOperations` and its three faces as the model, add a second read method to the shared bean and expose it as a REST path, a GraphQL query and an MCP tool, confirming all three return the same result for the same order id."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): one shared function backing a REST endpoint, a GraphQL resolver and a tool-calling interface, so the same input returns the same result no matter which transport a caller used."
> Check: calling the same operation through each of the three transports with the same input always returns the same result, and diverging one transport's implementation from the shared logic is the only way to make that stop being true.

## Build, run, observe

```bash
./verify.sh
```

Run from the mcp-first-single-bean example, this brings up PostgreSQL
alongside the service, reads a seeded order's summary through REST, GraphQL
and the MCP tool's handshake, and confirms all three results are identical
and that `tools/list` matches the committed tool manifest. It ends at
14/14.

Try:

- Change the GraphQL face to return a different value for one field instead
  of delegating to `OrderOperations` and rerun: the cross-face equality
  check fails while each face individually still runs.
- Call `tools/list` over the MCP endpoint by hand and compare the schema it
  advertises for `orderSummary` to the committed tool manifest.

## Anti-patterns

- **Reimplementing the same logic behind each transport.** Three independent implementations of one read give no guarantee that calling it through a different transport returns the same answer.
- **Diverging one face from the shared bean.** Changing one transport to stop delegating still passes a check on that face alone, the pattern's own negative witness, while a cross-face comparison fails.
- **Forcing truly different operations through one method.** A REST endpoint and a GraphQL query answering meaningfully different questions about the same entity gain nothing from a shared method; it only fakes a consistency that was never there.

## When not to use it

A shared bean behind every transport only pays off when the transports
actually serve the same operation; a REST endpoint and a GraphQL query that
answer meaningfully different questions about the same entity gain nothing
from being forced through one method; forcing them together just hides a
real difference behind a false appearance of consistency. A read this cheap
to recompute also gets little benefit from the delegation discipline that
matters far more once a write, a transaction or a side effect is involved.

## References

- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
- GraphQL Specification: [spec.graphql.org](https://spec.graphql.org/)
- Quarkus MCP Server: [docs.quarkiverse.io/quarkus-mcp-server/dev/index.html](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html)
- Quarkus SmallRye GraphQL guide: [quarkus.io/guides/smallrye-graphql](https://quarkus.io/guides/smallrye-graphql)
