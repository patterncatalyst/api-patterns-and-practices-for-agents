-- Spans Order and Payment (two schemas, one database), so the commit path's atomic saga can
-- update both in a single JDBC transaction, same shape as ch03's cancel-and-refund saga.
CREATE TABLE IF NOT EXISTS orders.orders (
    id          varchar(64) PRIMARY KEY,
    customer_id varchar(64) NOT NULL,
    item_sku    varchar(64) NOT NULL,
    quantity    integer NOT NULL,
    amount      numeric(12, 2) NOT NULL,
    status      varchar(16) NOT NULL
);

CREATE TABLE IF NOT EXISTS payments.payments (
    id       varchar(64) PRIMARY KEY,
    order_id varchar(64) NOT NULL,
    amount   numeric(12, 2) NOT NULL,
    status   varchar(16) NOT NULL
);
