package com.patterncatalyst.agentapi.packageapiasmcptools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The tool face of the triad: one coarse, reshaped tool ({@link #orderStatus}) and two narrow
 * supporting reads ({@link #paymentStatus}, {@link #shipmentTracking}). All three are read-only,
 * marked with {@code readOnlyHint} so a client can tell without calling them. {@code orderStatus}
 * is the point of this chapter: it does not return the raw {@code orders.orders} row and leave an
 * agent to call two more tools for the payment and the shipment; it composes all three reads into
 * one response, the "reshape, not wrap" move over ch12's single-bean bridge.
 */
@ApplicationScoped
public class OrderMcpTools {

    @Inject
    OrderStatusReads ops;

    @Inject
    ObjectMapper objectMapper;

    // Every tool here is a plain read against a fixed local domain: readOnlyHint and
    // idempotentHint true, destructiveHint and openWorldHint false.
    @Tool(description = "Get an order's composed status: its own state, its payment status and "
            + "amount, and its shipment carrier, tracking number and status, all in one response. "
            + "Use this instead of calling the order, payment and shipment reads separately.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public String orderStatus(@ToolArg(description = "The order id, for example O-9001.") String orderId) {
        return toJson(ops.orderStatus(orderId));
    }

    @Tool(description = "Get only the payment status and amount for an order id, with nothing "
            + "about the order's own state or its shipment.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public String paymentStatus(@ToolArg(description = "The order id, for example O-9001.") String orderId) {
        return toJson(ops.paymentStatus(orderId));
    }

    @Tool(description = "Get only the shipment carrier, tracking number and status for an order "
            + "id, with nothing about the order's own state or its payment.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public String shipmentTracking(@ToolArg(description = "The order id, for example O-9001.") String orderId) {
        return toJson(ops.shipmentTracking(orderId));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize the result", e);
        }
    }
}
