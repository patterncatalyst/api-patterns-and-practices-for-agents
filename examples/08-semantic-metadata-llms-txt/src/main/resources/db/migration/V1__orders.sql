CREATE TABLE IF NOT EXISTS orders.orders (
    id          varchar(64) PRIMARY KEY,
    customer_id varchar(64) NOT NULL,
    item_sku    varchar(64) NOT NULL,
    quantity    integer NOT NULL,
    amount      numeric(12, 2) NOT NULL,
    status      varchar(16) NOT NULL
);
