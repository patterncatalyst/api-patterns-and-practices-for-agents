-- The saga's own record of an order's fulfillment: one row per order, advanced by each step
-- (OrderFulfillmentSagaRoutes) and rolled back to COMPENSATED by the compensation that undoes the
-- reservation. carrier is filled only once the agent step (CarrierDecisionBean) books it.
CREATE TABLE IF NOT EXISTS orders.order_fulfillment (
    order_id    varchar(64) PRIMARY KEY,
    item_sku    varchar(32) NOT NULL,
    quantity    integer NOT NULL,
    amount      numeric(12, 2) NOT NULL,
    status      varchar(24) NOT NULL,
    carrier     varchar(16),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- The stock the reserve/release step works against: a minimal, demo-local stand-in for
-- inventory-service's own ledger, seeded with enough stock that only a declined payment, never a
-- stock shortfall, drives this example's failure path.
CREATE TABLE IF NOT EXISTS inventory.inventory_item (
    sku               varchar(32) PRIMARY KEY,
    quantity_on_hand  integer NOT NULL
);
INSERT INTO inventory.inventory_item (sku, quantity_on_hand) VALUES
    ('SKU-20-A', 50),
    ('SKU-20-B', 50)
ON CONFLICT (sku) DO NOTHING;

-- One row per order the payment step actually took money for; a declined payment writes no row
-- here, and a compensated payment flips status to REFUNDED rather than being deleted.
CREATE TABLE IF NOT EXISTS payments.payment (
    order_id    varchar(64) PRIMARY KEY,
    amount      numeric(12, 2) NOT NULL,
    status      varchar(16) NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- The carrier booking the agent step writes once a payment is confirmed; a cancelled booking
-- flips status to CANCELLED rather than being deleted, the same idempotent-by-guard shape as the
-- other two compensations.
CREATE TABLE IF NOT EXISTS shipping.shipment (
    order_id    varchar(64) PRIMARY KEY,
    carrier     varchar(16) NOT NULL,
    status      varchar(16) NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);
