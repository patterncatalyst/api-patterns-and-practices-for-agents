Order **O-9001** (via the `apa-orders` MCP server):

| Field | Value |
|---|---|
| Order status | `SHIPPED` |
| Payment status | `COMPLETED` (amount 88.50) |
| Shipment carrier | `FASTLINE` |
| Tracking number | `FL-778821` |

The shipment is currently `IN_TRANSIT`.

One call to `orderStatus` returned all three, since it composes the order, payment, and shipment reads into a single response.
