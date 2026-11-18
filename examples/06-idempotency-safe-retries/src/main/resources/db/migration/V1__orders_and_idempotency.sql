CREATE TABLE IF NOT EXISTS orders.orders (
    id          varchar(64) PRIMARY KEY,
    customer_id varchar(64) NOT NULL,
    item_sku    varchar(64) NOT NULL,
    quantity    integer NOT NULL,
    amount      numeric(12, 2) NOT NULL,
    status      varchar(16) NOT NULL
);

-- The Idempotent Consumer EIP's own duplicate-detection table (camel-sql's
-- JdbcMessageIdRepository, schema-qualified via setTableName("orders.idempotency_consumer")).
-- Created here by Flyway rather than left to the repository's own lazy create-on-start, so its
-- existence never races Camel's context startup against Flyway's own migration. The column
-- names and the PRIMARY KEY are exactly what JdbcMessageIdRepository's DEFAULT_CREATE_STRING
-- expects (unquoted identifiers; PostgreSQL folds them to lower case the same way on every
-- query the repository issues, so the case used here does not matter as long as it is
-- unquoted, consistently, in both the table and the repository's generated SQL).
-- PRIMARY KEY (processorName, messageId) is the DB-level backstop behind the EIP's own
-- duplicate check: two concurrent requests for the same Idempotency-Key race to insert the
-- same key, and the database allows only one of them to win.
CREATE TABLE IF NOT EXISTS orders.idempotency_consumer (
    processorName varchar(255) NOT NULL,
    messageId     varchar(100) NOT NULL,
    createdAt     timestamp NOT NULL,
    PRIMARY KEY (processorName, messageId)
);

-- First-result replay store: the actual stored response for a given Idempotency-Key, so a
-- repeat call can replay it verbatim instead of re-running the saga. order_id (not
-- idempotency_key) is the primary key on purpose: under normal operation the Idempotent
-- Consumer EIP above guarantees at most one row is ever written per key, so replayOrReject
-- always reads back the first (and only) row for that key, "first-result replay" by
-- construction. If that guard is ever bypassed, PlaceOrderSaga.replayOrReject still orders by
-- created_at and replays only the first row written, rather than guessing which one is right.
CREATE TABLE IF NOT EXISTS orders.idempotent_responses (
    order_id        varchar(64) PRIMARY KEY,
    idempotency_key varchar(255) NOT NULL,
    request_hash    varchar(64) NOT NULL,
    status_code     integer NOT NULL,
    response_body   text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_idempotent_responses_key ON orders.idempotent_responses (idempotency_key);
