package com.patterncatalyst.agentapi.packageapiasmcptools;

import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.PromptArg;
import io.quarkiverse.mcp.server.PromptMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The prompt face of the triad: a templated starting message for summarizing one order's composed
 * status, built from {@link OrderStatusReads#orderStatus}, the same read the {@code orderStatus}
 * tool serves, so the prompt and the tool never disagree about the underlying data.
 */
@ApplicationScoped
public class OrderStatusSummaryPrompt {

    @Inject
    OrderStatusReads ops;

    @Prompt(description = "Summarize an order's composed status (order, payment, shipment) in "
            + "plain language for a customer.")
    public PromptMessage summarizeOrderStatus(
            @PromptArg(description = "The order id, for example O-9001.") String orderId) {
        ComposedOrderStatus status = ops.orderStatus(orderId);
        return PromptMessage.withUserRole("""
                Summarize this order's status for a customer in two or three plain-language \
                sentences. Do not invent any detail not given below.

                Order %s for customer %s: %d x %s, total %s.
                Order status: %s.
                Payment status: %s, amount %s.
                Shipment: carrier %s, tracking number %s, status %s.
                """.formatted(status.orderId(), status.customerId(), status.quantity(), status.itemSku(),
                status.amount(), status.orderStatus(), status.paymentStatus(), status.paymentAmount(),
                status.carrier(), status.trackingNumber(), status.shipmentStatus()));
    }
}
