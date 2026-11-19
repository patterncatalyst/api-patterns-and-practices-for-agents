---
title: "Securing and Gating MCP Tools"
order: 16
part: "Build: MCP packaging"
description: "An MCP tool surface is scoped and gated the same way any other credentialed API is, not left open because the caller happens to be a model."
duration: "25 minutes"
---

## Learning objectives

- Require a verified bearer token on an MCP endpoint before any tool call is dispatched.
- Gate a read by role and a destructive write by scope, on top of that authentication.
- Add a human confirmation step in front of a mutation an agent alone should not be trusted to commit.
- Recognize an open tool surface, prompt-as-business-logic and a silently mis-gated annotation as the ways a gate fails without anyone noticing.

## Why this matters

An agent with private data, untrusted content and an unguarded path to act on both is the lethal trifecta: a tool surface left open because the caller happens to be a model, rather than a browser or another service, is exactly that path. Scoping which authenticated caller may read, and which may write, is the same identity work any credentialed API already does; an MCP tool gets no exemption from it just because a model, not a person, is holding the token.

{% include excalidraw.html file="16-gated-mcp-tools" alt="Three stacked guards in front of a two-tool MCP surface: an unauthenticated call is rejected before the MCP dispatcher runs, a role gates the read, and a scope plus an Elicitation confirm gate the write" caption="Figure 16.1: Each tool adds its own requirement on top of the endpoint's own gate" %}

## Pattern: Securing and Gating MCP Tools

### The problem

An MCP endpoint left open to any caller, or gated only at the transport level with every tool equally reachable once a caller is in, gives a model the same destructive reach as a read, with nothing distinguishing a caller allowed to look from one allowed to act. A business rule written only into a tool's description ("only cancel an order the customer actually owns") is advice a model can forget or be talked out of, not a guard that holds regardless of what the model decides to do with it.

### The solution

**The whole tool endpoint requires a verified bearer token before any call is dispatched, a role gates which authenticated caller may invoke a given tool, and a destructive write adds its own scope requirement plus an explicit human confirmation before anything is written.**

### How Quarkus models it

`quarkus.http.auth.permission.mcp.paths=/mcp` with `policy=authenticated` rejects an unauthenticated call before the MCP dispatcher ever runs, ahead of any per-tool check. `orderSummary`, a plain read, carries only a role:

```java
@Tool(description = "Get an order's id, customer, item, quantity, amount and status.")
@RolesAllowed("agent")
@WrapBusinessError(OrderNotFoundException.class)
public String orderSummary(@ToolArg(description = "The order id.") String orderId) { ... }
```

`cancelOrder` adds a scope check, by hand, ahead of an `Elicitation` confirm:

```java
@Tool(description = "Cancel an order and refund its amount, after the person at the keyboard "
        + "explicitly confirms. Requires the mcp:tools:write scope.")
@RolesAllowed("agent")
@WrapBusinessError({ OrderNotFoundException.class, OrderNotCancellableException.class })
public String cancelOrder(@ToolArg(description = "The order id.") String orderId,
        @ToolArg(description = "Why the order is being cancelled.") String reason, Elicitation elicitation) {
    requireWriteScope();
    OrderRepository.OrderRow order = orders.find(orderId);
    if (!order.isCancellable()) throw new OrderNotCancellableException(orderId, order.status());
    ElicitationResponse response = elicitation.requestBuilder()
            .setMessage("Cancel order " + orderId + " and refund " + order.amount() + "? Reason: " + reason)
            .addSchemaProperty("confirmCancellation", ElicitationRequest.BooleanSchema.builder()
                    .setTitle("Confirm cancellation").setRequired(true).build())
            .build().sendAndAwait();
    if (!response.actionAccepted()) return toJson(CancelOrderResult.rejectedByUser(orderId));
    orders.cancelAndRefund(orderId);
    return toJson(CancelOrderResult.cancelled(orderId, order.amount()));
}
```

The scope check reads the token's `scope` claim by hand rather than through `@PermissionsAllowed("mcp:tools:write")`: that annotation's own value splits on the first colon into a permission name and an action, so a colon-separated scope name collides with the split (it checks for a permission named `mcp` with action `tools:write`, never the whole string a bearer token's scope claim actually carries). A declined confirmation is not an error: it returns a typed `REJECTED_BY_USER` result and writes nothing, the same as a human saying no to a prompt. A failed role or scope check throws `ForbiddenException`, which the MCP runtime already recognizes as a security failure the same way an unmet `@RolesAllowed` does, needing no extra wrapping of its own.

Two operational notes worth carrying forward from standing the identity provider up in containers: a container reaches the identity provider at one URL internally for key discovery and a different, published URL is what an externally fetched token actually names as its issuer, so the two must be configured separately rather than assumed to be the same address; and stdio, the transport a directly attached process reads, must stay disabled in a detached container, since a container with no attached standard input otherwise shuts itself down within seconds of start.

Removing the scope guard from `cancelOrder` lets an under-scoped, read-only token reach the elicitation confirm instead of being rejected outright: the mutation path still requires a human to accept before anything writes, but the caller that should never have reached that confirm in the first place gets there anyway, isolating exactly the scope gate this pattern depends on.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must let only an authenticated, correctly scoped caller reach a destructive operation, and must hold that operation for an explicit human confirmation before it writes anything.
> **Contract shape:** operation intent "read an order" (role-gated) versus "cancel an order" (role- and scope-gated, confirmed); inputs an order id, plus a reason for the cancellation; response shape the order summary for the read, a cancelled-or-rejected result for the write; error shape forbidden for a missing role or scope, not-found or not-cancellable for a bad order id or state; idempotency key not needed, repeat confirmation attempts are each a fresh decision.
> **Build it here:** "Using `OrderMcpTools.cancelOrder` as the model, add a second destructive tool gated by a different scope, and confirm a token carrying only the read scope can reach neither the existing write tool nor the new one."
> **Build it elsewhere:** "Implement the same pattern in Python: a FastAPI service behind an MCP server built with the official Python MCP SDK, checking a bearer token's role for a read and its scope plus an explicit confirmation step for a destructive write, so an under-scoped caller never reaches the mutation."
> Check: a caller without the write scope can read but never mutate, and a caller with the write scope still has to clear an explicit confirmation before anything is written; removing either the scope check or the confirmation is the only way to make either guarantee fail.

## Build, run, observe

```bash
./verify.sh
```

Run from the securing-gating-mcp-tools example, this brings up the stack with the identity provider, confirms an unauthenticated call to the endpoint is rejected, confirms a read-scoped token can read but not cancel, and confirms a write-scoped token clears the scope gate and reaches the confirmation step. It ends at 15/15.

Try:

- Fetch a token carrying both the read and write scopes and see the cancellation reach the same confirmation a write-only token does.
- Call the read tool for an order id that does not exist and see the instructive tool error the wrapped exception produces.
- Decode an issued token and compare its role and scope claims to the committed scope table.

## Anti-patterns

- **An unauthenticated or ungated tool surface.** Leaving the endpoint reachable without a verified caller, or every tool equally reachable once authenticated, is the lethal-trifecta exposure: private data, an untrusted caller and no gate between them.
- **Prompt-as-business-logic.** Writing a rule like "only cancel an order the caller is allowed to touch" only into a tool's description, with nothing actually enforcing it once the call is dispatched.
- **A silently mis-gated annotation.** Reaching for a framework's convenience annotation for a scope check without confirming how it parses the scope string; a colon-separated scope name splitting on the wrong character gates nothing while looking gated.

## When not to use it

A role, a scope and a confirmation step cost a caller an extra round trip and a human a decision to make; a read with no sensitive data behind it, or a write that is trivially reversible and already audited elsewhere, does not need the same weight as an irreversible cancellation and refund. Gating every tool identically regardless of what it actually does trains the person confirming requests to click through without reading them, which defeats the one guard a confirmation step exists to provide.

## References

- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
- Simon Willison, *The lethal trifecta for AI agents*: [simonwillison.net/2025/Jun/16/the-lethal-trifecta](https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/)
- Quarkus MCP Server: [docs.quarkiverse.io/quarkus-mcp-server/dev/index.html](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html)
- Keycloak documentation: [keycloak.org/documentation](https://www.keycloak.org/documentation)
