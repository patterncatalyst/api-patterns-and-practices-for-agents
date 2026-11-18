---
layout: plan
title: "Domain model"
render_with_liquid: false
---

# Order-to-delivery domain model

This book mirrors the order-to-delivery shipping domain used by the
event-driven-patterns-with-quarkus-and-camel sibling, which is itself
Enterprise Integration Patterns' running example. This file states the facts
this book uses, in its own words; it does not rewrite the sibling's domain
page.

## Rules

- Never invent a new entity or a new area. If a pattern seems not to fit, the chapter's framing is wrong, not the domain.
- Entity names are fixed. Reuse across chapters is the point.
- Event names are messages about these entities, never new entities.
- Carriers are FASTLINE (road) and AIRCO (air), both fictional. Never a real carrier name.
- Shared entity records live once, in `examples/domain-model/`, package `com.patterncatalyst.agentapi`. Event payload records live in each example.

## The business

A customer orders an item online. The order is recorded, stock is reserved,
the payment is taken, a carrier is booked, and the parcel is delivered. Five
services own one step each and talk through events on Kafka. This book adds
one more layer on top: an AI agent needs to call these services too, which is
why the APIs in front of them are built differently from an API meant only
for a human-driven UI.

## Services

| Service | Owns | Reacts to | Emits |
|---|---|---|---|
| order-service | `Order`, its lifecycle | InventoryInsufficient, PaymentProcessed, PaymentFailed, ShipmentScheduled, ShipmentDelivered | OrderPlaced, OrderCancelled |
| inventory-service | `InventoryItem` stock | OrderPlaced, OrderCancelled, PaymentFailed | InventoryReserved, InventoryInsufficient |
| payment-service | `Payment` | InventoryReserved, OrderCancelled | PaymentProcessed, PaymentFailed |
| shipping-service | `Shipment`, carrier choice | PaymentProcessed | ShipmentScheduled, ShipmentInTransit, ShipmentDelivered |
| notification-service | `Notification` | every event | none |

## Records and enums

`examples/domain-model`, plain Java 25, compact-constructor validation, one
JUnit negative witness per rule. The module itself lands in r02; this is the
planned shape.

| Type | Fields | Validation |
|---|---|---|
| `Order` | `id`, `customerId`, `itemSku`, `quantity`, `amount` | text fields non-blank; quantity > 0; amount > 0; amount is `BigDecimal` with 2 decimals, HALF_UP |
| `InventoryItem` | `sku`, `quantityOnHand` | quantity >= 0 |
| `Payment` | `id`, `orderId`, `amount`, `status` | amount > 0 |
| `Shipment` | `id`, `orderId`, `carrier`, `trackingNumber`, `status` | carrier non-null |
| `Notification` | `id`, `orderId`, `customerId`, `eventType` | text fields non-blank |
| `Topics` | string constants for the catalog topics | none; no example hard-codes a topic name |

| Enum | Values |
|---|---|
| `OrderStatus` | PLACED, PAID, SHIPPED, DELIVERED, CANCELLED |
| `PaymentStatus` | PENDING, COMPLETED, FAILED, REFUNDED |
| `ShipmentStatus` | PENDING, SCHEDULED, IN_TRANSIT, DELIVERED |
| `Carrier` | FASTLINE, AIRCO |

Order ids are text, for example `O-1001`. Amounts are `BigDecimal` with 2
decimals (HALF_UP).

## Event catalog

Topic convention: **`apa.<domain>.<event>`**. Names use dots and hyphens only,
never underscores.

| Event | Producer | Consumers | Topic |
|---|---|---|---|
| OrderPlaced | order | inventory, notification | `apa.orders.placed` |
| OrderCancelled | order | inventory, notification | `apa.orders.cancelled` |
| InventoryReserved | inventory | payment, notification | `apa.inventory.reserved` |
| InventoryInsufficient | inventory | order, notification | `apa.inventory.insufficient` |
| PaymentProcessed | payment | shipping, order, notification | `apa.payments.processed` |
| PaymentFailed | payment | inventory, order, notification | `apa.payments.failed` |
| ShipmentScheduled | shipping | order, notification | `apa.shipping.scheduled` |
| ShipmentInTransit | shipping | notification | `apa.shipping.in-transit` |
| ShipmentDelivered | shipping | order, notification | `apa.shipping.delivered` |

## Lifecycles

- Order: PLACED to PAID to SHIPPED to DELIVERED, one step forward at a time. CANCELLED is allowed from PLACED or PAID only. DELIVERED and CANCELLED are terminal.
- Shipment: PENDING to SCHEDULED to IN_TRANSIT to DELIVERED.

## Persistence

One `apa` database with a schema per service (`orders`, `inventory`,
`payments`, `shipping`, `notifications`), created by each service's Flyway
migrations against the schemas declared in
`examples/_infra/postgres/init/01-schemas.sql`.

## The agent-facing layer

What this book adds on top of the sibling's domain: every one of these
services gets a second front door. Part 1 reshapes its REST and GraphQL
endpoints for an agent caller (intent-named operations, search over raw
pagination, instructive errors). Part 2 packages that surface as MCP tools.
Part 3 lets an agent react to the same Kafka events a human dashboard would
poll for. Part 7's final project treats order-service, inventory-service and
shipping-service as the source domains for 3 to 4 agent-facing data products
in a focused multi-domain data mesh, following Dehghani's four principles
(domain ownership, data as a product, self-serve platform, federated
computational governance), reusing `datamesh-reference-arch-quarkus` by
reference rather than by copy.

## Chapter remap

This fills in as chapters ship, starting r02. r01 ships no chapters.
