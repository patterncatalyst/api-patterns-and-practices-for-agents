-- The durable record this chapter's pattern is about: one row per model call, carrying the
-- call's own token counts and the cost derived from them at TokenCostRecorder's fixed rates.
-- Schema-qualified and owned only by this example; no other service reads or writes agenttraffic.
CREATE TABLE IF NOT EXISTS agenttraffic.token_cost (
    call_id        varchar(64) PRIMARY KEY,
    model          varchar(128),
    input_tokens   integer NOT NULL,
    output_tokens  integer NOT NULL,
    cost_usd       numeric(14,6) NOT NULL,
    recorded_at    timestamptz NOT NULL DEFAULT now()
);
