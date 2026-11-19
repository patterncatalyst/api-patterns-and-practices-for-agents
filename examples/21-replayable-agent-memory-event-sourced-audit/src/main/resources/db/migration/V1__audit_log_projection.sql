-- The live projection, folded only from AuditAppendRoute's committed Kafka records
-- (shipping.audit_log_projection), and a scratch table ReplayService rebuilds from scratch on
-- every /audit/replay call by reading the same log from its beginning
-- (shipping.audit_replay). Both share the same shape; "id" is the audit record's own identity,
-- so folding the same committed record twice (a redelivery, or a full replay) changes nothing.
CREATE TABLE IF NOT EXISTS shipping.audit_log_projection (
    id          varchar(64) PRIMARY KEY,
    direction   varchar(16) NOT NULL,
    order_id    varchar(64) NOT NULL,
    actor       varchar(160) NOT NULL,
    action      varchar(160) NOT NULL,
    detail      text NOT NULL,
    tokens_in   integer,
    tokens_out  integer,
    cost_usd    numeric(14, 6),
    occurred_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_log_projection_order_id ON shipping.audit_log_projection (order_id);

CREATE TABLE IF NOT EXISTS shipping.audit_replay (
    id          varchar(64) PRIMARY KEY,
    direction   varchar(16) NOT NULL,
    order_id    varchar(64) NOT NULL,
    actor       varchar(160) NOT NULL,
    action      varchar(160) NOT NULL,
    detail      text NOT NULL,
    tokens_in   integer,
    tokens_out  integer,
    cost_usd    numeric(14, 6),
    occurred_at timestamptz NOT NULL
);
