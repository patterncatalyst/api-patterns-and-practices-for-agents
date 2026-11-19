package com.patterncatalyst.agentapi.multiagentchoreography.order;

import com.patterncatalyst.agentapi.Order;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The MODEL=off decision path for the order agent: placing an order has no branch to decide
 * (unlike the shipping agent's carrier choice), so there is no recorded-decision fixture file
 * to replay, only the one deterministic action calling the tool directly. {@code mvn -B verify}
 * and CI never need a model. Either path (this one, or {@link OrderAdvisor} over Ollama) reaches
 * the same {@link OrderPlacementTool#placeOrder}, so the durable record, the counter, the log
 * line and the published event this book's checks assert on are identical whichever path
 * produced them.
 */
@ApplicationScoped
public class OrderFixtureAdvisor {

    @Inject
    OrderPlacementTool tool;

    public void place(Order order) {
        tool.placeOrder(order.id(), order.customerId(), order.itemSku(),
                Integer.toString(order.quantity()), order.amount().toPlainString());
    }
}
