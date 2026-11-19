# Order status playbook

How to read the fields the `orderStatus` tool returns, and when a composed status needs a
follow-up action rather than a plain summary.

## Order status

`PLACED` the order is recorded, nothing paid or shipped yet. `PAID` payment is taken, nothing
shipped yet. `SHIPPED` a shipment is booked and moving. `DELIVERED` and `CANCELLED` are terminal:
nothing moves after either.

## Payment status

`PENDING` not yet taken. `COMPLETED` taken in full. `FAILED` needs the customer to retry with a
different method. `REFUNDED` returned after a cancellation.

## Shipment status

`PENDING` no carrier booked yet. `SCHEDULED` booked, not yet moving. `IN_TRANSIT` moving, a
tracking number is present. `DELIVERED` arrived.

## Carriers

`FASTLINE` road. `AIRCO` air.

## When to flag instead of summarize

Flag an order for a human instead of summarizing it when the order status is `PAID` or later but
the payment status is not `COMPLETED`, or when the order status is `SHIPPED` but the shipment
status is still `PENDING`. Either combination means two of the three reads disagree about what
happened to the order.
