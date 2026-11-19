package com.patterncatalyst.agentapi.publishthetoolsurface;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The committed tool surface: one coarse, composed read ({@link #orderStatus}) and two narrow
 * supporting reads ({@link #paymentStatus}, {@link #shipmentTracking}), all read-only against the
 * fixed in-memory {@link OrderCatalog}. This same jar serves these three tools whether it is run
 * detached behind Streamable HTTP or piped stdin/stdout as stdio: the triad never changes shape
 * with the transport, only the version reported alongside it does, and that version always
 * matches the one in the pom.
 */
@ApplicationScoped
public class OrderMcpTools {

    @Inject
    OrderCatalog catalog;

    @Inject
    ObjectMapper objectMapper;

    @Tool(description = "Get an order's composed status: its own state, its payment status and "
            + "amount, and its shipment carrier, tracking number and status, all in one response. "
            + "Use this instead of calling the order, payment and shipment reads separately.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public String orderStatus(@ToolArg(description = "The order id, for example O-9001.") String orderId) {
        return toJson(catalog.orderStatus(orderId));
    }

    @Tool(description = "Get only the payment status and amount for an order id, with nothing "
            + "about the order's own state or its shipment.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public String paymentStatus(@ToolArg(description = "The order id, for example O-9001.") String orderId) {
        return toJson(catalog.paymentStatus(orderId));
    }

    @Tool(description = "Get only the shipment carrier, tracking number and status for an order "
            + "id, with nothing about the order's own state or its payment.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public String shipmentTracking(@ToolArg(description = "The order id, for example O-9001.") String orderId) {
        return toJson(catalog.shipmentTracking(orderId));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize the result", e);
        }
    }
}
