-- The durable record of each order status consultation the agent performed over the remote
-- orderStatus MCP tool, written by OrderStatusRecorder regardless of which path (the real
-- @McpToolBox-driven AiService, or FixtureConsumer under MODEL=off) produced it. Both the status
-- and the tracking number are read out of the remote tool's own JSON result, so the row is proof
-- the remote tool was actually reached: the tracking number in particular is a value the model has
-- no way to learn except by calling it.
CREATE TABLE IF NOT EXISTS consumer.agent_order_status_consultation (
    order_id         varchar(64) PRIMARY KEY,
    order_status     varchar(32) NOT NULL,
    tracking_number  varchar(64),
    consulted_at     timestamptz NOT NULL DEFAULT now()
);
