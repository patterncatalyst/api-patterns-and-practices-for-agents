CREATE TABLE IF NOT EXISTS payments.payments (
    id       varchar(64) PRIMARY KEY,
    order_id varchar(64) NOT NULL UNIQUE,
    amount   numeric(12, 2) NOT NULL,
    status   varchar(16) NOT NULL
);
