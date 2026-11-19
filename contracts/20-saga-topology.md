# ch20 order-fulfillment saga topology

This saga is purely in-process and HTTP-triggered: an agent calls `POST
/orders`, and every step runs as a direct Camel route in the same JVM,
coordinated by Camel's in-memory `CamelSagaService`. It consumes no topic
and emits no event, so no AsyncAPI document applies; this file is the
committed contract instead, documenting the steps, their compensations and
the saga's shape, the same role an AsyncAPI document plays for the
event-triggered examples in this part.

## Trigger

`POST /orders` (`OrderFulfillmentResource`), body an `Order`
(`com.patterncatalyst.agentapi.Order`: `id`, `customerId`, `itemSku`,
`quantity`, `amount`). The call starts the saga synchronously and the
response already reflects the outcome: `201` once every step has completed,
`422` once a failed step's compensations have already unwound the earlier
ones. `GET /orders/{id}` reads the same `orders.order_fulfillment` row
either way.

## Steps and compensations

| Order | Step | Compensation | Guard the compensation checks |
|---|---|---|---|
| 1 | Reserve stock (`FulfillmentService.reserveStock`) | Release stock (`releaseStock`) | `orders.order_fulfillment.status = 'STOCK_RESERVED'` |
| 2 | Take payment (`FulfillmentService.takePayment`) | Refund payment (`refundPayment`) | `payments.payment.status = 'COMPLETED'` and `orders.order_fulfillment.status = 'PAID'` |
| 3 | Book a carrier, an agent decision (`CarrierDecisionBean.bookCarrier`, `CarrierAdvisor` or `FixtureCarrierAdvisor`, `CarrierBookingTool.bookCarrier`) | Cancel the booking (`cancelBooking`) | `shipping.shipment.status = 'BOOKED'` and `orders.order_fulfillment.status = 'COMPLETED'` |

Each step is wrapped in its own nested `saga()` block (Camel Saga EIP,
propagation `MANDATORY`, joining the outer saga), with its own
`.compensation(...)` endpoint and its own `.option("orderId", ...)` so the
compensation route, invoked on a fresh exchange, still has the order id as
a header. The outer `direct:fulfillOrder` route owns the saga's lifecycle:
propagation `REQUIRES_NEW`, `.timeout(fulfillment.saga.timeout)`, and
completion mode left at the default, `AUTO`. If every step returns
normally the saga completes; if any step throws (a declined payment, or a
stock shortfall), the exception propagates back through the `direct:` call
chain to the outer saga, which compensates every step enlisted so far, in
reverse order.

## Coordinator

`org.apache.camel.saga.InMemorySagaService` (shipped in `camel-support`,
already on the classpath through `camel-quarkus-saga`), registered once by
`InMemorySagaRegistration` and used unconditionally: no external LRA
coordinator, no Narayana, in either the live compose run or `mvn -B
verify`. State lives only in the JVM's memory for the life of the saga; a
saga does not survive an application restart.

## Idempotence

Every compensation is a single guarded `UPDATE` that only takes effect from
the exact prior state it undoes (the guard column in the table above), so
calling it twice changes nothing the second time. `OrderFulfillmentSagaTest`
calls each of the three compensations twice directly to prove it.
