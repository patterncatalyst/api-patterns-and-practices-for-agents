-- The durable record of each outbound call's business result, written by
-- OutboundCallResultRecorder regardless of which path (the real model-driven AuditedToolCaller
-- call, or the same call made directly under MODEL=off) produced it, and regardless of whether
-- an audit record was appended for that call (audit.interceptor.enabled). Both the status and the
-- tracking number are read out of the remote tool's own JSON result, so the row is proof the
-- remote tool was actually reached: the tracking number in particular is a value the model has no
-- way to learn except by the call reaching it.
CREATE TABLE IF NOT EXISTS consumer.outbound_call_result (
    order_id         varchar(64) PRIMARY KEY,
    order_status     varchar(32) NOT NULL,
    tracking_number  varchar(64),
    consulted_at     timestamptz NOT NULL DEFAULT now()
);
