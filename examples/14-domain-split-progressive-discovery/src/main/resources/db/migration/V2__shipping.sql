CREATE TABLE IF NOT EXISTS shipping.shipments (
    id              varchar(64) PRIMARY KEY,
    order_id        varchar(64) NOT NULL,
    carrier         varchar(16) NOT NULL,
    tracking_number varchar(64),
    status          varchar(16) NOT NULL
);
