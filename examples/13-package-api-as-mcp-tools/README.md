# 13 Package an API as MCP tools

Goes past ch12's one-bean, one-tool bridge into the full MCP packaging surface: a tool, a
resource, and a prompt, served from one Quarkus application, over the transport an agent's own
process actually needs.

- `OrderMcpTools.orderStatus` is a **coarse, reshaped** tool: it composes the order's own state
  (`orders.orders`), its payment (`payments.payments`) and its shipment (`shipping.shipments`)
  into one response. `paymentStatus` and `shipmentTracking` stay as narrow, single-purpose reads
  for a caller that only needs one of the three. All three carry `readOnlyHint`.
- `OrderStatusPlaybookResource` serves `skill://orders/status-playbook`, a static markdown
  playbook loaded from the classpath (jar-safe; never `Path.of` against the filesystem), so a
  caller can fetch the rules for reading a composed status once instead of re-deriving them.
- `OrderStatusSummaryPrompt` serves `summarizeOrderStatus`, a templated prompt that calls the same
  `OrderStatusReads.orderStatus` read the tool calls, so the prompt and the tool never disagree.

`OrderStatusReads` holds the three reads once; `OrderMcpTools` and `OrderStatusSummaryPrompt` both
delegate to it unchanged. This example has nothing to write, so there is no Camel route: a read
has no event to publish.

## Transport by audience

Streamable HTTP is served at `/mcp`, the `quarkus-mcp-server-http` default root path, and is what
`verify.sh` drives live. `quarkus-mcp-server-stdio` stays enabled, the extension default, for a
direct, attached `java -jar` consumer, which needs no HTTP-reachable port at all. The compose
container runs detached (`-d`) and has no stdin attached, so stdio there reads immediate EOF and
shuts the application down a few seconds after start; `%prod.quarkus.mcp.server.stdio.enabled=false`
disables it only in that prod-mode, detached context, carrying forward ch12's exact fix.

## The negative witness

The point of a coarse `orderStatus` tool is that a caller gets the payment and the shipment
without a second and third call. To confirm the "composed status" check actually enforces that,
`OrderStatusReads.orderStatus` was changed by hand to return only the order row, with the payment
and shipment fields left `null`, which is what a passthrough-over-reshape mistake looks like. With
that change in place, `tools/call orderStatus` still returns `O-9001`'s order state, but the
composed-status check, which asserts `paymentStatus`, `carrier`, `trackingNumber` and
`shipmentStatus` are all present, fails. Restoring the three-way composition returns the response
to byte-identical with the committed example and the check passes again. The bite is recorded in
`_plans/verification-log.md`.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack comes up, the MCP handshake negotiates protocol revision `2025-11-25`, `tools/list`,
`resources/list` and `prompts/list` are checked against the committed `contracts/13-*.mcp.json`
files, `orderStatus` is called for the seeded order `O-9001` and its composed response is checked,
and the stack is torn down. Set `KEEP=1` to leave the stack running.

- Try `resources/read` for `skill://orders/status-playbook` by hand and compare it to the
  committed markdown.
- Try `prompts/get` for `summarizeOrderStatus` with a different `orderId` argument and see which
  fields the rendered message pulls in.
- Try calling `paymentStatus` and `shipmentTracking` on their own and compare their narrower
  responses to what `orderStatus` composes.
