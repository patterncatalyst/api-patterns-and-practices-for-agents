-- Mirrors ch11's order+payment pair (the same cancel-and-refund side effect), plus the one
-- thing this chapter adds: a durable, Kafka-resumed pending-decision/decision gate that holds
-- the side effect until a human (or automated) decision arrives.
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

-- The gate itself: one row per proposeCancelAndRefund tool call, held PENDING until a decision
-- resumes it. correlation_id is both the Kafka correlation key (apa.orders.pending-decisions,
-- apa.orders.decisions) and the idempotency key a redelivered decision is checked against (see
-- CancelGateService.claimGate).
CREATE TABLE IF NOT EXISTS orders.cancel_gate (
    correlation_id varchar(64) PRIMARY KEY,
    order_id       varchar(64) NOT NULL,
    reason         varchar(256) NOT NULL DEFAULT '',
    reason_code    varchar(32) NOT NULL,
    amount         numeric(12, 2) NOT NULL,
    status         varchar(24) NOT NULL,
    proposed_at    timestamptz NOT NULL DEFAULT now(),
    decided_at     timestamptz
);

-- The exactly-once ledger: one row per correlation id that ever actually executed the
-- cancel-and-refund side effect, so a redelivered APPROVE provably never re-executes it (the
-- row count for a correlation id stays 1 no matter how many times APPROVE is redelivered).
CREATE TABLE IF NOT EXISTS orders.cancel_execution_log (
    correlation_id varchar(64) PRIMARY KEY,
    order_id       varchar(64) NOT NULL,
    executed_at    timestamptz NOT NULL DEFAULT now()
);
