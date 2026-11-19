-- The notification agent's durable record: the choreography's terminal effect.
CREATE TABLE IF NOT EXISTS notifications.notifications (
    id           varchar(64) PRIMARY KEY,
    order_id     varchar(64) NOT NULL,
    customer_id  varchar(64) NOT NULL,
    event_type   varchar(64) NOT NULL,
    sent_at      timestamptz NOT NULL DEFAULT now()
);
