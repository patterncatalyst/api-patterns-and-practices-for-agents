-- The shipping agent's durable record: the shipment it scheduled in reaction to OrderPlaced.
CREATE TABLE IF NOT EXISTS shipping.shipments (
    id               varchar(64) PRIMARY KEY,
    order_id         varchar(64) NOT NULL UNIQUE,
    carrier          varchar(16) NOT NULL,
    tracking_number  varchar(64) NOT NULL,
    status           varchar(16) NOT NULL,
    scheduled_at     timestamptz NOT NULL DEFAULT now()
);
