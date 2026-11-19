-- The durable record the consumed ShipmentDelivered event produces: one customer notification per
-- (order_id, event_type). The composite primary key is also the uniqueness guarantee the
-- idempotency witness depends on: redelivering the same event can never produce a second row.
CREATE TABLE IF NOT EXISTS notify.notifications (
    id           varchar(64) NOT NULL,
    order_id     varchar(64) NOT NULL,
    event_type   varchar(32) NOT NULL,
    customer_id  varchar(64) NOT NULL,
    message      text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (order_id, event_type)
);
