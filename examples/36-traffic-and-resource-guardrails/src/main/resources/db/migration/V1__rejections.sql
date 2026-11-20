CREATE TABLE trafficguardrails.rejections (
    id BIGSERIAL PRIMARY KEY,
    reason TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
