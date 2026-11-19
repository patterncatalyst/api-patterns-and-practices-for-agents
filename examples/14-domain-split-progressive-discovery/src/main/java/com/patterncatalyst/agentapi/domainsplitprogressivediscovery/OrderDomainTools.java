package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The order server's live tool surface, kept small: two namespaced reads
 * ({@code order_}-prefixed, since a client talking to several of this book's domain servers sees
 * one flat {@code tools/list} per connection and the prefix is the only thing keeping names
 * readable). Everything else an order caller might want (search, batch-get, place, cancel,
 * refund, history) stays in the catalog {@link OperationCatalog} serves instead of a live tool
 * here; see {@link CatalogSearchTools} and {@link CatalogResource}.
 */
@ApplicationScoped
@McpServer("order")
public class OrderDomainTools {

    @Inject
    OrderReads reads;

    @Inject
    ObjectMapper objectMapper;

    @Tool(name = "order_status_get",
            description = "Get this order's own lifecycle status, customer, item and amount, read "
                    + "from the order schema only (no payment or shipment data).",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public String orderStatusGet(@ToolArg(description = "The order id, for example O-7001.") String orderId) {
        return toJson(reads.status(orderId));
    }

    @Tool(name = "order_cancel_eligibility_get",
            description = "Get whether this order can still be cancelled right now, derived from "
                    + "its current lifecycle status.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public String orderCancelEligibilityGet(@ToolArg(description = "The order id, for example O-7001.") String orderId) {
        return toJson(reads.cancelEligibility(orderId));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize the result", e);
        }
    }
}
