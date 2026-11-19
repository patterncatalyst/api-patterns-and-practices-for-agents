# 15 Reshape not wrap: heterogeneous protocols

The one gRPC-behind-a-facade example in this book: a **separate `payment-service` image** speaks
gRPC and nothing else, and the `mcp-facade` image puts an agent-facing MCP tool surface in front of
it. Two Quarkus applications, two Containerfiles, two compose services; `payment-service` has no
MCP, REST or GraphQL endpoint of its own, and `mcp-facade` has no database of its own.

- `contracts/15-payment.proto` is the committed contract: `Payment`, `TakePaymentRequest`,
  `RefundRequest`, `PaymentResponse` and a `PaymentStatus` enum mirroring
  `com.patterncatalyst.agentapi.PaymentStatus`, with every amount as `int64` cents (proto3 has no
  decimal type). The same file is compiled into both images; each Containerfile copies it from
  `contracts/` over the module's own `src/main/proto/payment.proto` at build time, so the image
  never drifts from the committed copy.
- `payment-service` implements `PaymentService` with `@GrpcService` (`PaymentGrpcService`), backed
  by Postgres (`payments.payments`, Flyway-seeded) through the shared
  `com.patterncatalyst.agentapi.Payment`/`PaymentStatus` domain types. `PaymentGrpcMapper` is the
  only place `BigDecimal` crosses to `int64` cents on this side of the wire. Failures become
  `StatusRuntimeException`s (`INVALID_ARGUMENT`, `NOT_FOUND`, `FAILED_PRECONDITION`), never a raw
  stack trace over gRPC.
- `mcp-facade` exposes `takePayment` and `refundPayment` as MCP tools. `AmountReshape` is the
  reshape: a JSON decimal amount (`"49.99"`) becomes the proto's `int64` cents on the way in, and a
  `PaymentResponse`'s cents and `PaymentStatus` become a lean, decimal, instructive `PaymentView`
  JSON on the way out, through a `@GrpcClient("payment-service")` stub (`PaymentMcpTools`) over a
  real gRPC channel to the separate image. A `StatusRuntimeException` becomes a
  `PaymentFacadeException` with a message an agent can act on, never a bare gRPC status code.

## Transport and networking

`payment-service` serves gRPC on the quarkus-grpc default separate server port (9000), reachable
only inside the compose network (`payment-service:9000`); no host port is published for it, since
the proto contract is an internal implementation detail an agent never calls directly. `mcp-facade`
serves Streamable HTTP at `/mcp`, published on `127.0.0.1:8080` like every other example in this
book; `quarkus-mcp-server-stdio` stays enabled (the extension default) for a direct, attached
`java -jar` consumer and is disabled only in the detached compose container
(`%prod.quarkus.mcp.server.stdio.enabled=false`), carrying forward ch12/13/14's fix.

## The negative witness

Bypassing `AmountReshape` (for example wiring `takePayment`'s cents straight off an unscaled
`amount` field, or returning `Payment.getAmountCents()` unformatted instead of
`AmountReshape.toDecimal(...)`) must turn a `$49.99` payment into a `$0.49` payment at the
separate `payment-service` image, or make the facade's amount field round-trip as raw cents instead
of the decimal string `"49.99"`. `verify.sh`'s
`take_payment_reshapes_the_decimal_amount_through_a_real_grpc_call` check asserts the correct
round-trip and fails against either mistake; restoring `AmountReshape` returns the response to
byte-identical with the committed example. The bite that confirms it is recorded in
`_plans/verification-log.md`.

## Build, run, observe

```bash
./verify.sh
```

A fresh two-image stack comes up, the MCP handshake negotiates protocol revision `2025-11-25`,
`tools/list` is checked against the committed `contracts/15-payment-tools.mcp.json`, `takePayment`
takes a payment for a fresh order through a real gRPC call to the separate `payment-service` image
and the decimal round-trip is checked, `refundPayment` refunds the seeded order `O-9001`, and an
unknown order produces an instructive tool error rather than a raw gRPC status. The stack is torn
down. Set `KEEP=1` to leave the stack running.

Intended live check list (for the orchestrator's live run):

- [ ] All 8 containers healthy, including both `apa-15-payment-service` and `apa-15-mcp-facade`.
- [ ] `initialize` negotiates protocol revision `2025-11-25`.
- [ ] `tools/list` advertises `takePayment` and `refundPayment`, each with an `inputSchema`.
- [ ] `contracts/15-payment-tools.mcp.json` captured from this live run and committed to match.
- [ ] `takePayment("O-7777", "49.99")` returns `{"orderId":"O-7777","amount":"49.99","status":"COMPLETED"}`.
- [ ] `refundPayment("O-9001", "49.99")` returns `{"orderId":"O-9001","amount":"49.99","status":"REFUNDED"}`.
- [ ] `refundPayment("O-0000", "10.00")` returns an MCP tool error whose text contains "No payment found", not a gRPC status code.
- [ ] The negative witness (see above) makes the round-trip check fail, and restoring the fix makes it pass again.

- Try calling `payment-service`'s gRPC port directly with `grpcurl` (from inside the `apa` network)
  and compare the raw `int64` cents to what `mcp-facade` reshapes.
- Try `takePayment` twice for the same order id and see the upsert in `payments.payments` keep one
  row per order.
- Try `refundPayment` twice for the same order and see the second call fail with
  `FAILED_PRECONDITION`, reshaped into an instructive message.
