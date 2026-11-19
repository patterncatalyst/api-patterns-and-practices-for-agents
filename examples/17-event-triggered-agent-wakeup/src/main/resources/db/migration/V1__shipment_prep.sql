-- The durable record the woken agent's one @Tool writes: which carrier it chose to prep an
-- order's shipment with, once, per order. Schema-qualified so the statement reads the same
-- whether Dev Services (tests) or compose (the shared apa database) backs it.
CREATE TABLE IF NOT EXISTS shipping.shipment_prep (
    order_id     varchar(64) PRIMARY KEY,
    carrier      varchar(16) NOT NULL,
    prepared_at  timestamptz NOT NULL DEFAULT now()
);
