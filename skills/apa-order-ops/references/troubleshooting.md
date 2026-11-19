# Troubleshooting

## `initialize failed` or connection refused

The `apa-orders` server is not reachable at the configured URL (`APA_ORDERS_MCP_URL`, default
`http://localhost:8080/mcp`). Start it: `examples/23-building-a-claude-code-skill`'s
`compose.yaml` reuses ch13's server and publishes it on `127.0.0.1:8080`. Run
`docker compose -f compose.yaml up -d --wait` from that directory, or `./verify.sh` for a fresh
run that tears itself down afterward (`KEEP=1` to leave it running).

## An operation is "not advertised by the server"

`scripts/order-status.sh` checks `tools/list` before calling anything and refuses to call a
name the server never advertised, rather than guessing at a result. This happens when the
operation name is misspelled, or when it names a capability `apa-orders` genuinely does not
have (for example a write operation like canceling or deleting an order: this server is
read-only). Check `references/order-operations.md` for the three names that are actually
advertised, and `tools/list` itself for what is currently live, since the two must always
agree.

## The order id is not found

`orderId` values follow the `O-####` shape; `O-9001` is the one order ch13 and every example
that reuses its server seeds. An order id outside that seeded set returns an error from the
tool, not a fabricated status. Report the error, do not invent a plausible-looking status.

## The composed and narrow tools disagree

They never should: `orderStatus`'s `paymentStatus` and `shipmentTracking` fields come from the
same rows `paymentStatus` and `shipmentTracking` read directly. If a caller sees a difference,
treat it as a bug report against the server, not something to paper over in the answer.
