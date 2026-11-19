package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The shipping server's live tool surface, the same size as the order server's: two namespaced
 * reads. Scheduling, rescheduling, cancelling a shipment, switching carrier, the label and
 * proof-of-delivery references, and shipment history all stay catalog-only; see
 * {@link CatalogSearchTools} and {@link CatalogResource}.
 */
@ApplicationScoped
@McpServer("shipping")
public class ShippingDomainTools {

    @Inject
    ShippingReads reads;

    @Inject
    ObjectMapper objectMapper;

    @Tool(name = "shipping_track_get",
            description = "Track this shipment: its carrier, tracking number and lifecycle status, "
                    + "read from the shipping schema only (no order or payment data).",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public String shippingTrackGet(@ToolArg(description = "The order id, for example O-7001.") String orderId) {
        return toJson(reads.tracking(orderId));
    }

    @Tool(name = "shipping_eta_get",
            description = "Get a plain-language estimated delivery window computed from the "
                    + "shipment's current lifecycle status.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public String shippingEtaGet(@ToolArg(description = "The order id, for example O-7001.") String orderId) {
        return toJson(reads.eta(orderId));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize the result", e);
        }
    }
}
