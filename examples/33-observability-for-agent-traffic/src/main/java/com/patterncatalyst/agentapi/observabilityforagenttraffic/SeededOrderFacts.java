package com.patterncatalyst.agentapi.observabilityforagenttraffic;

import com.patterncatalyst.agentapi.Carrier;
import com.patterncatalyst.agentapi.OrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

/**
 * A few seeded order facts, held in memory, so {@link OrderStatusTool} has something real to
 * answer from without a database of its own: this example's one durable table is
 * {@code agenttraffic.token_cost}, the record {@link TokenCostRecorder} writes, never the order
 * facts themselves.
 */
@ApplicationScoped
public class SeededOrderFacts {

    /** One seeded order's status, carrier and tracking number (absent before shipping). */
    public record Fact(String orderId, OrderStatus status, Carrier carrier, String trackingNumber) {
    }

    private static final Map<String, Fact> SEEDED = Map.of(
            "O-3301", new Fact("O-3301", OrderStatus.PLACED, null, null),
            "O-3302", new Fact("O-3302", OrderStatus.SHIPPED, Carrier.AIRCO, "AC-3302"),
            "O-3303", new Fact("O-3303", OrderStatus.DELIVERED, Carrier.FASTLINE, "FL-3303"));

    public Fact find(String orderId) {
        return SEEDED.get(orderId);
    }
}
