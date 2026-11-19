-- The durable record of every reviewed refund proposal: the order id, the full proposal
-- (the agent's recorded run, serialized as JSON, an auditable record of exactly what was
-- reviewed), the deterministic verdict and, on REJECT, the one clause that failed. id is a
-- generated row key, not a natural key: the same order can be reviewed more than once (a
-- resubmitted or corrected proposal), and verify.sh's negative witness reviews the same violating
-- run twice, once against each rule file.
CREATE TABLE IF NOT EXISTS review.reviews (
    id            varchar(64) NOT NULL PRIMARY KEY,
    order_id      varchar(64) NOT NULL,
    proposal      jsonb       NOT NULL,
    verdict       varchar(16) NOT NULL,
    failed_clause varchar(64),
    reviewed_at   timestamptz NOT NULL DEFAULT now()
);
