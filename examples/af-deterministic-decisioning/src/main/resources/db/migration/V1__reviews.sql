-- The durable record of every reviewed refund proposal: the order id, the customer id, the
-- proposed amount and reason, the Drools-decided verdict and, on REJECT, the one clause that
-- failed. id is a generated row key, not a natural key: the same order can be reviewed more than
-- once (a resubmitted proposal, or verify.sh's negative witness reviewing the same over-limit
-- proposal twice, once against each rule resource).
CREATE TABLE IF NOT EXISTS refunds.reviews (
    id             varchar(64)    NOT NULL PRIMARY KEY,
    order_id       varchar(64)    NOT NULL,
    customer_id    varchar(64)    NOT NULL,
    refund_amount  numeric(12, 2) NOT NULL,
    reason         varchar(64)    NOT NULL,
    verdict        varchar(16)    NOT NULL,
    failed_clause  varchar(64),
    reviewed_at    timestamptz    NOT NULL DEFAULT now()
);
