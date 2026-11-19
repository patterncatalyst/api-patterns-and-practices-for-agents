---
name: apa-order-ops
description: Use when an agent needs an order's status, payment, or shipment tracking from the apa-orders MCP server, for example "what is the status of order O-9001" or "has O-9001 shipped yet".
---

# Order operations over apa-orders

The `apa-orders` MCP server (registered the same way `22-consuming-an-mcp-server-from-claude-code`
registers it, over Streamable HTTP) exposes three read-only tools for the order-to-delivery
surface: `orderStatus`, `paymentStatus`, and `shipmentTracking`. Each takes one input,
`orderId` (for example `O-9001`), and each is safe to call any number of times: none of them
change state.

## Answering an order question

1. Default to `orderStatus`. It composes the order's own state, its payment, and its shipment
   into one response, so most questions ("what is happening with this order") need exactly one
   call.
2. Call `paymentStatus` or `shipmentTracking` instead only when the question is narrowly about
   payment or shipment and the composed response would be more than the caller needs.
3. Never invoke a tool name that `tools/list` does not advertise. The server rejects an
   unadvertised name; do not guess at one, and do not report a result you did not get back from
   the server.
4. When the order status and the payment or shipment status disagree in a way that signals
   trouble, say so plainly instead of smoothing it over. See
   `references/order-operations.md` for the exact field values and what each combination means.

## Running it from the command line

`scripts/order-status.sh <orderId> [operation]` drives the same lookup directly, without a
running Claude Code session: it opens its own MCP session against the server, confirms the
requested operation is actually advertised, calls it, and prints the result. It defaults to
`orderStatus`:

```bash
scripts/order-status.sh O-9001
scripts/order-status.sh O-9001 paymentStatus
```

The server URL defaults to `http://localhost:8080/mcp` (where
`examples/23-building-a-claude-code-skill`'s compose stack publishes ch13's server); override it
with `APA_ORDERS_MCP_URL` when the server runs elsewhere.

## Reference material

- `references/order-operations.md`: every operation's input and output shape, and the field
  values (order, payment, and shipment status, and the two carriers) worth knowing before
  reading a result.
- `references/troubleshooting.md`: what a connection failure, an unknown order id, or an
  unadvertised operation name looks like, and what to do about each.
