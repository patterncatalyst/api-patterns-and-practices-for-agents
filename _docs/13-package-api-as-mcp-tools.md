---
title: "Package an API as MCP Tools"
order: 13
part: "Build: MCP packaging"
description: "quarkus-mcp-server packages an existing API's operations as registered MCP tools, resources and prompts."
duration: "25 minutes"
---

## Learning objectives

- Package a set of reads as an MCP tool, resource and prompt from one Quarkus application.
- Reshape several narrow reads into one coarse, composed tool response instead of wrapping each endpoint one for one.
- Choose Streamable HTTP or stdio by who is actually consuming the surface, not by habit.
- Recognize naive 1:1 wrapping and context-dumped tool descriptions as the anti-patterns a reshaped triad avoids.

## Why this matters

An agent asking "where does this order stand" against a literally wrapped API makes three separate tool calls, one per resource, and reassembles the answer in its own context. Packaging an existing API as MCP tools is not a transport swap: the same chattiness a REST client tolerated becomes three tool-selection decisions and three round trips an agent has to get right in sequence, with the composition work moved onto the caller instead of the server that already has every row it needs.

{% include excalidraw.html file="13-mcp-triad" alt="A naive 1:1 API-to-MCP wrap leaves an agent to call three separate tools and assemble the answer itself; the tool/resource/prompt triad composes the same three reads into one tool response, backs it with a resource fetched once, and a prompt reading the same composed data" caption="Figure 13.1: Compose for the question, not for the endpoint" %}

## Pattern: Package an API as MCP Tools

### The problem

Wrapping each existing endpoint as its own MCP tool, one for one, carries every chattiness problem the wrapped API already had straight into the tool surface: a caller who wants a single order's full picture still issues a separate call for the order, the payment and the shipment, holds all three results, and stitches them together itself. Nothing about the new transport removes the stitching; it only renames each call a "tool" instead of a "request."

### The solution

**A tool's shape answers the question a caller actually asks, composed server-side from whatever reads it takes, while a resource carries the standing reference material a tool description should not have to repeat, and a prompt packages a proven way to ask for the answer.**

### How Quarkus models it

`OrderStatusReads` holds three reads once: an order's own row, its payment, its shipment, each against its own schema. `orderStatus` is the coarse read, composing the other two into one record:

```java
public ComposedOrderStatus orderStatus(String orderId) {
    OrderRow order = orderRow(orderId);
    PaymentStatusView payment = paymentStatus(orderId);
    ShipmentTrackingView shipment = shipmentTracking(orderId);
    return new ComposedOrderStatus(order.id(), order.customerId(), order.itemSku(), order.quantity(),
            order.amount(), order.status(), payment.status(), payment.amount(), shipment.carrier(),
            shipment.trackingNumber(), shipment.status());
}
```

`OrderMcpTools` exposes that composition as one `@Tool`, and keeps the two narrower reads available in their own right for a caller who only wants one of them:

```java
@Tool(description = "Get an order's composed status: its own state, its payment status and "
        + "amount, and its shipment carrier, tracking number and status, all in one response. "
        + "Use this instead of calling the order, payment and shipment reads separately.",
        annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                idempotentHint = true, openWorldHint = false))
public String orderStatus(@ToolArg(description = "The order id, for example O-9001.") String orderId) {
    return toJson(ops.orderStatus(orderId));
}
```

`OrderStatusPlaybookResource` serves `skill://orders/status-playbook`, a static markdown file loaded from the classpath rather than `Path.of`, the only form that still resolves once the class ships inside a runtime jar's own layers instead of a loose file on a development filesystem; a caller fetches it once instead of a tool description re-deriving the same status rules on every call. `OrderStatusSummaryPrompt` renders `summarizeOrderStatus` by calling the same `OrderStatusReads.orderStatus` method the tool calls, so the prompt and the tool never disagree about the data behind them. This example has nothing to write, so there is no Camel route: a read has no event to publish.

Streamable HTTP, served at `/mcp`, is what an HTTP-reachable caller uses and is what drives a live run. stdio stays enabled, the extension's default, for a directly attached process that needs no published port at all; a detached container has no standard input for stdio to read, so stdio is disabled only in the profile serving that container, while an attached `java -jar` consumer keeps it on.

Reducing `orderStatus` to a passthrough of the raw order row, leaving the payment and shipment fields absent instead of delegating to the two narrower reads, breaks the composed-response guarantee without breaking the call itself: `tools/call orderStatus` still succeeds and still returns the order's own state, but the fields a caller relied on the composition for come back empty, isolating exactly the reshape the tool exists to do.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must let an agent get one order's full standing, order state, payment state and shipment state, in a single call, never as a sequence of narrower reads it has to assemble itself.
> **Contract shape:** operation intent "get this order's composed status"; inputs an order id; response shape order state plus payment state plus shipment state in one object; error shape a not-found result when no order, payment or shipment row exists for the id; idempotency key not needed, this is a read.
> **Build it here:** "Using `OrderStatusReads` and `OrderMcpTools` as the model, add a fourth read (for example a cancellation-eligibility flag) to the composed `orderStatus` response without adding a new narrow tool for it, and confirm the existing narrow tools stay unchanged."
> **Build it elsewhere:** "Implement the same pattern in Python: a FastAPI service holding the three reads, fronted by an MCP server built with the official Python MCP SDK exposing one composed tool and two narrow tools, so the same composed call returns the same three-way result."
> Check: calling the composed tool once returns every field a caller would otherwise need three separate calls to assemble, and narrowing the tool back to one domain's fields is the only way to make that stop being true.

## Build, run, observe

```bash
./verify.sh
```

Run from the package-api-as-mcp-tools example, this brings up the stack, negotiates the MCP handshake, and checks `tools/list`, `resources/list` and `prompts/list` against their committed contracts before calling `orderStatus` for a seeded order and checking its composed response. It ends at 20/20.

Try:

- Call `resources/read` for the status playbook by hand and compare it to the committed markdown.
- Call `prompts/get` for `summarizeOrderStatus` with a different order id and see which fields the rendered message pulls in.
- Call `paymentStatus` and `shipmentTracking` on their own and compare their narrower responses to what `orderStatus` composes.

## Anti-patterns

- **Naive 1:1 API-to-MCP wrapping.** Turning each existing endpoint into its own tool preserves the exact chattiness the API already had, now behind tool calls instead of HTTP calls.
- **Chatty CRUD for agents.** Three separate tool calls to answer one question an agent asked once, instead of a single tool composed for that question.
- **Dumping reference material into every tool description.** Repeating standing rules (what each status value means, when three signals disagree enough to need a human) inside a tool's own description bloats the context cost of every call with text only some callers need, instead of a resource a caller fetches once and reuses.

## When not to use it

Composing several reads into one tool only pays off when those reads are usually wanted together. A caller that only ever wants a shipment's tracking number pays for a coarse response it did not ask for if the only tool available is the composed one; that is why the narrow reads stay available in their own right rather than being folded away once the composed tool exists. A single read with nothing else worth composing alongside it gains nothing from this pattern beyond what one plain tool already gives it.

## References

- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
- Anthropic, *Writing effective tools for AI agents*: [anthropic.com/engineering/writing-tools-for-agents](https://www.anthropic.com/engineering/writing-tools-for-agents)
- Quarkus MCP Server: [docs.quarkiverse.io/quarkus-mcp-server/dev/index.html](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html)
