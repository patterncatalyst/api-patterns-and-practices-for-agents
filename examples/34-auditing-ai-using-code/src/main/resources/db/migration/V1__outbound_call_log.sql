-- The live projection, folded only from AuditAppendOutboundRoute's committed Kafka records
-- (audit.outbound_call_log), and a scratch table ReplayService rebuilds from scratch on every
-- /audit/replay call by reading the same log from its beginning (audit.outbound_call_replay).
-- Both share the same shape; "id" is the audit record's own identity, so folding the same
-- committed record twice (a redelivery, or a full replay) changes nothing.
CREATE TABLE IF NOT EXISTS audit.outbound_call_log (
    id               varchar(64) PRIMARY KEY,
    correlation_id   varchar(64) NOT NULL,
    direction        varchar(16) NOT NULL,
    target           varchar(160) NOT NULL,
    argument_digest  varchar(64) NOT NULL,
    occurred_at      timestamptz NOT NULL,
    tokens_in        integer,
    tokens_out       integer,
    cost_usd         numeric(14, 6)
);
CREATE INDEX IF NOT EXISTS idx_outbound_call_log_correlation_id ON audit.outbound_call_log (correlation_id);

CREATE TABLE IF NOT EXISTS audit.outbound_call_replay (
    id               varchar(64) PRIMARY KEY,
    correlation_id   varchar(64) NOT NULL,
    direction        varchar(16) NOT NULL,
    target           varchar(160) NOT NULL,
    argument_digest  varchar(64) NOT NULL,
    occurred_at      timestamptz NOT NULL,
    tokens_in        integer,
    tokens_out       integer,
    cost_usd         numeric(14, 6)
);
