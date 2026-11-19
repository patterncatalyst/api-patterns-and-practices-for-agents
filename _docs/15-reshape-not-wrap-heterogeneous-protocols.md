---
title: "Reshape not Wrap: Heterogeneous Protocols"
order: 15
part: "Build: MCP packaging"
description: "A gRPC service sits behind the same agent-facing facade as a REST or GraphQL one, reshaped for the caller instead of wrapped unchanged."
duration: "25 minutes"
---

## Learning objectives

- Put an MCP tool facade in front of a separate service that speaks a different protocol entirely.
- Reshape a value crossing a protocol boundary (a JSON decimal amount against proto3's integer-only numerics) in both directions, not just on the way in.
- Turn a protocol-level failure into an instructive tool error instead of leaking the protocol across the facade.
- Recognize wrapping a heterogeneous protocol unchanged, and an unwrapped business error, as the two ways this boundary leaks.

## Why this matters

An agent calling a tool should never need to know what protocol sits behind it. A gRPC service speaks proto3, which has no decimal type, so a $49.99 payment exists on that side of the wire as the integer 4999. A facade that passes that integer through unchanged, or that lets a `StatusRuntimeException` reach the caller as a raw gRPC status, has not packaged the service as an MCP tool; it has relocated the gRPC service's own wire format one hop closer to the agent.

{% include excalidraw.html file="15-reshape-grpc" alt="A separate gRPC payment-service image sits behind an MCP tool facade; the facade reshapes a decimal amount to int64 cents on the way in and back to decimal on the way out, and turns a gRPC status into an instructive tool error instead of leaking the protocol across" caption="Figure 15.1: Reshape at the boundary, never wrap a heterogeneous protocol through unchanged" %}

## Pattern: Reshape not Wrap: Heterogeneous Protocols

### The problem

Putting an MCP tool directly over a gRPC, SOAP or any other non-HTTP-JSON service and passing its values through unexamined keeps that protocol's own representation alive on the far side of the facade: integer cents instead of a decimal amount, an enum ordinal instead of a readable status, a `StatusRuntimeException` instead of a message naming what went wrong and what to try next. A caller gains a new transport without gaining anything that protocol's own shape cost it.

### The solution

**A facade over a heterogeneous protocol reshapes every value crossing the boundary into the form an agent actually needs, in both directions, and turns a protocol-level failure into an instructive tool error, rather than passing the other protocol's representation through unchanged.**

### How Quarkus models it

The payment-service and the mcp-facade are two separate Quarkus applications, two images, two compose services: payment-service speaks gRPC only, defined by the committed `payment.proto`, and has no MCP, REST or GraphQL endpoint of its own; mcp-facade has no database of its own and calls payment-service over a real gRPC channel. `AmountReshape` is the one place the decimal boundary is crossed, in both directions:

```java
static long toCents(String decimalAmount) {
    BigDecimal amount = new BigDecimal(decimalAmount).setScale(2, RoundingMode.HALF_UP);
    if (amount.signum() <= 0) {
        throw new IllegalArgumentException("amount must be a positive decimal, got " + decimalAmount);
    }
    return amount.movePointRight(2).longValueExact();
}

static String toDecimal(long cents) {
    return BigDecimal.valueOf(cents, 2).toPlainString();
}
```

`PaymentMcpTools.takePayment` reshapes on the way in, calls the separate service over `@GrpcClient("payment-service")`, and reshapes the response back on the way out:

```java
@Tool(description = "Take a payment for an order. amount is a decimal string, for example "
        + "\"49.99\". Returns the order id, the decimal amount taken and the resulting "
        + "payment status.")
@WrapBusinessError(PaymentFacadeException.class)
public String takePayment(@ToolArg(description = "The order id.") String orderId,
        @ToolArg(description = "The amount, as a decimal string, for example 49.99.") String amount) {
    TakePaymentRequest request = TakePaymentRequest.newBuilder()
            .setOrderId(orderId).setAmountCents(AmountReshape.toCents(amount)).build();
    return callAndReshape(() -> paymentService.takePayment(request).await().indefinitely());
}
```

A `StatusRuntimeException` from the gRPC call is turned into a message an agent can act on before it ever reaches the tool result: `NOT_FOUND` becomes "No payment found for that order", `FAILED_PRECONDITION` becomes "That payment cannot be refunded." A thrown `PaymentFacadeException` only becomes that instructive, `isError` MCP result because `takePayment` and `refundPayment` are annotated `@WrapBusinessError(PaymentFacadeException.class)`; without that annotation the same exception surfaces as an opaque protocol error instead, no different from the gRPC status it was built to replace. `payment-service`'s gRPC port is reachable only inside the compose network, with no host port published for it, since the proto contract behind the facade is an implementation detail an agent never calls directly; the facade's own Streamable HTTP endpoint is the one surface published for a caller.

Bypassing `AmountReshape`, for example wiring `takePayment`'s cents straight off the unscaled decimal string, turns a $49.99 payment into a $49 one: the round trip still completes, the call still succeeds, but the number an agent reads back is wrong, isolating exactly the reshape this pattern exists to perform.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must let an agent call a service that speaks a different protocol entirely without ever seeing that protocol's own representation, numeric encoding or failure shape.
> **Contract shape:** operation intent "take or refund a payment"; inputs an order id and a decimal amount; response shape the order id, the decimal amount and a readable status; error shape an instructive message naming what was wrong, never a protocol-level status code; idempotency key not needed beyond the underlying service's own state check.
> **Build it here:** "Using `AmountReshape` and `PaymentMcpTools` as the model, add a third tool that reads a payment's current status without mutating it, reshaping the same cents-to-decimal boundary on the way out only, and confirm an unknown order still returns an instructive message rather than a gRPC status."
> **Build it elsewhere:** "Implement the same pattern in Python: a FastAPI-fronted MCP server built with the official Python MCP SDK, calling a separate gRPC service with a Python gRPC client, reshaping the same decimal-to-cents boundary in both directions and turning a gRPC status into an instructive tool error."
> Check: a value that crosses the protocol boundary round-trips back to its original decimal form, and a gRPC failure always reaches the caller as a readable message, never a status code; skipping the reshape in either direction is the only way to make either guarantee fail.

## Build, run, observe

```bash
./verify.sh
```

Run from the reshape-grpc-behind-facade example, this brings up both images alongside the shared infrastructure, negotiates the MCP handshake against the facade, takes and refunds a payment through a real gRPC call to the separate service, and checks that an unknown order returns an instructive tool error. It ends at 15/15.

Try:

- Call the payment-service's gRPC port directly with a gRPC client from inside the compose network and compare the raw integer cents to what the facade reshapes.
- Call `takePayment` twice for the same order and see the underlying row stay at one per order.
- Call `refundPayment` twice for the same order and see the second call fail, reshaped into an instructive message.

## Anti-patterns

- **Wrap, not reshape, across protocols.** Leaking Protobuf fields, integer cents or a gRPC status code through to an agent instead of translating them at the boundary.
- **An unwrapped business error.** A thrown exception that reaches the caller as a protocol-level failure instead of an instructive tool error, because the method never declared which exceptions to wrap.

## When not to use it

A reshaping facade costs an extra service boundary and an extra translation layer to maintain; a protocol that already speaks the shape an agent needs, JSON over HTTP with decimal numerics and readable error bodies, gains nothing from being wrapped in a facade whose only job would be to pass values through unchanged. The pattern earns its cost at an actual protocol mismatch, not as a default front end for every internal service.

## References

- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
- Quarkus MCP Server: [docs.quarkiverse.io/quarkus-mcp-server/dev/index.html](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html)
- Quarkus gRPC guide: [quarkus.io/guides/grpc](https://quarkus.io/guides/grpc)
