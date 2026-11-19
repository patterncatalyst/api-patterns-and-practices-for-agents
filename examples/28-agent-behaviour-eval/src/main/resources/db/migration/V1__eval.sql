-- The durable record of every scored agent-behaviour transcript: the run and order ids, the full
-- transcript (the captured tool calls, token usage and durable outcome, serialized as JSON, an
-- auditable record of exactly what was scored), the deterministic verdict and, on FAIL, the one
-- assertion that failed. id is a generated row key, not a natural key: the same run id can be
-- scored more than once (verify.sh scores the committed regressed-run transcript, then again
-- after the mutation check, and the good-run transcript both through GET /eval/run/good and
-- through a raw POST /eval of the same fixture text).
CREATE TABLE IF NOT EXISTS eval.eval_runs (
    id                 varchar(64) NOT NULL PRIMARY KEY,
    run_id             varchar(64) NOT NULL,
    order_id           varchar(64) NOT NULL,
    transcript         jsonb       NOT NULL,
    verdict            varchar(16) NOT NULL,
    violated_assertion varchar(64),
    recorded_at        timestamptz NOT NULL DEFAULT now()
);
