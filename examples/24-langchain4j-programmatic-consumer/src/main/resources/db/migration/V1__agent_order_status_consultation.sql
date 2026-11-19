-- The durable record of each order status consultation the agent performed over the remote
-- orderStatus MCP tool, written by OrderStatusRecorder regardless of which path (the real
-- @McpToolBox-driven AiService, or FixtureConsumer under MODEL=off) produced it.
CREATE TABLE IF NOT EXISTS consumer.agent_order_status_consultation (
    order_id      varchar(64) PRIMARY KEY,
    order_status  varchar(32) NOT NULL,
    consulted_at  timestamptz NOT NULL DEFAULT now()
);
