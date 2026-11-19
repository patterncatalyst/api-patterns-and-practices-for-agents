# Order operations

The three tools the `apa-orders` MCP server advertises in `tools/list`. Each one is read-only
(`readOnlyHint: true`, `idempotentHint: true`) and takes one required input, `orderId`. The
headings below are the exact tool names: the check that proves this file stays accurate
(`23-building-a-claude-code-skill`'s `verify.sh`) extracts them and asserts each one still
appears in the live server's `tools/list`.

## `orderStatus`

Composes the order's own state, its payment, and its shipment into one response. Use this one
first; it answers "where does this order stand" in a single call.

Input: `{"orderId": "O-9001"}`

Output fields: `orderId`, `customerId`, `itemSku`, `quantity`, `amount`, `orderStatus`,
`paymentStatus`, `paymentAmount`, `carrier`, `trackingNumber`, `shipmentStatus`.

## `paymentStatus`

Only the payment: nothing about the order's own state or its shipment.

Input: `{"orderId": "O-9001"}`

Output fields: `orderId`, `paymentId`, `amount`, `status`.

## `shipmentTracking`

Only the shipment: nothing about the order's own state or its payment.

Input: `{"orderId": "O-9001"}`

Output fields: `orderId`, `shipmentId`, `carrier`, `trackingNumber`, `status`.

## Field values

Order status: `PLACED` recorded, nothing paid or shipped yet. `PAID` payment taken, nothing
shipped yet. `SHIPPED` a shipment is booked and moving. `DELIVERED` and `CANCELLED` are
terminal.

Payment status: `PENDING` not yet taken. `COMPLETED` taken in full. `FAILED` needs the customer
to retry with a different method. `REFUNDED` returned after a cancellation.

Shipment status: `PENDING` no carrier booked yet. `SCHEDULED` booked, not yet moving.
`IN_TRANSIT` moving, a tracking number is present. `DELIVERED` arrived.

Carriers: `FASTLINE` road. `AIRCO` air.

## When to flag instead of summarize

Flag an order for a human instead of summarizing it when the order status is `PAID` or later but
the payment status is not `COMPLETED`, or when the order status is `SHIPPED` but the shipment
status is still `PENDING`. Either combination means two of the three reads disagree about what
happened to the order.
