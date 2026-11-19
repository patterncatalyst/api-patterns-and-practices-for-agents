-- The order agent's durable record: the order as placed, schema-qualified so the statement
-- reads the same whether Dev Services (tests) or compose (the shared apa database) backs it.
CREATE TABLE IF NOT EXISTS orders.orders (
    id           varchar(64) PRIMARY KEY,
    customer_id  varchar(64) NOT NULL,
    item_sku     varchar(64) NOT NULL,
    quantity     integer NOT NULL,
    amount       numeric(12, 2) NOT NULL,
    placed_at    timestamptz NOT NULL DEFAULT now()
);
