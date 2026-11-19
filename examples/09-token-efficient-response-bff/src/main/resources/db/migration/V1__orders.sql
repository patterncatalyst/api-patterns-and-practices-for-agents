CREATE TABLE IF NOT EXISTS orders.orders (
    id                   varchar(64) PRIMARY KEY,
    record_uuid          uuid NOT NULL,
    customer_uuid        uuid NOT NULL,
    customer_id          varchar(64) NOT NULL,
    customer_full_name   varchar(128) NOT NULL,
    customer_email       varchar(128) NOT NULL,
    customer_risk_score  integer NOT NULL,
    item_sku             varchar(64) NOT NULL,
    quantity             integer NOT NULL,
    amount               numeric(12, 2) NOT NULL,
    status               varchar(16) NOT NULL,
    internal_notes       text NOT NULL,
    audit_trail          jsonb NOT NULL
);
