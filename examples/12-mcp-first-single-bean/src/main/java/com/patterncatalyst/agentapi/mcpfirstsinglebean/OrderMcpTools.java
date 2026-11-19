package com.patterncatalyst.agentapi.mcpfirstsinglebean;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Face 3: the MCP tool surface, kept shallow (one {@code @Tool}, no auth, no packaging or
 * discovery depth; that is Part 2's concern). Delegates to {@link OrderOperations}, unchanged,
 * and serializes the same {@link OrderSummary} instance the other two faces serve, so a caller
 * reading the tool's text content gets byte-identical JSON to what REST returns.
 */
@ApplicationScoped
public class OrderMcpTools {

    @Inject
    OrderOperations ops;

    @Inject
    ObjectMapper objectMapper;

    @Tool(description = "Get the lean order summary for an order id. Returns the same summary "
            + "the REST GET /orders/{id}/summary endpoint and the GraphQL orderSummary query return.")
    public String orderSummary(@ToolArg(description = "The order id, for example O-9001.") String orderId) {
        try {
            return objectMapper.writeValueAsString(ops.orderSummary(orderId));
        } catch (OrderNotFoundException notFound) {
            throw notFound;
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize order summary for " + orderId, e);
        }
    }
}
