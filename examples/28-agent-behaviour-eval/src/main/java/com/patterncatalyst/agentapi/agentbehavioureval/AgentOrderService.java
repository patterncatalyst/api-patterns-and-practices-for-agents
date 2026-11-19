package com.patterncatalyst.agentapi.agentbehavioureval;

import com.patterncatalyst.agentapi.Order;
import com.patterncatalyst.agentapi.OrderStatus;
import com.patterncatalyst.agentapi.Payment;
import com.patterncatalyst.agentapi.PaymentStatus;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one business operation the LIVE path's assistant performs: cancel a shipping order and
 * refund the customer. Holds the seeded order {@link #SEED_ORDER_ID} in memory, the same shape
 * 01-why-agent-apis's {@code OrderOpsService} uses, local to this example since event and
 * proposal-run payload shapes are never shared across chapters, only the domain-model entity
 * records themselves ({@link Order}, {@link Payment}). {@link LiveRunner} reads {@link
 * #status(String)} and {@link #isRefunded(String)} after the assistant's tool call to build the
 * live transcript's durable outcome, the same signal the committed fixtures record directly.
 */
@ApplicationScoped
public class AgentOrderService {

    static final String SEED_ORDER_ID = "O-28001";

    private final Map<String, OrderStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private Order order;

    @PostConstruct
    void init() {
        reset();
    }

    /** Restores the seeded order to its starting state, so a live run always starts the same way. */
    public synchronized void reset() {
        order = new Order(SEED_ORDER_ID, "CUST-28001", "SKU-28", 1, new BigDecimal("89.00"));
        statuses.put(order.id(), OrderStatus.PAID);
        payments.put(order.id(), new Payment("PAY-28001", order.id(), order.amount(), PaymentStatus.COMPLETED));
    }

    public OrderStatus status(String orderId) {
        require(orderId);
        return statuses.get(orderId);
    }

    /** Whether the order's payment has actually reached REFUNDED, not just whether it was cancelled. */
    public boolean isRefunded(String orderId) {
        require(orderId);
        Payment payment = payments.get(orderId);
        return payment != null && payment.status() == PaymentStatus.REFUNDED;
    }

    /** The single operation this chapter's tool exposes: cancel and refund in one step. */
    public synchronized Payment cancelAndRefund(String orderId, String reason) {
        require(orderId);
        OrderStatus current = statuses.get(orderId);
        if (current != OrderStatus.PAID && current != OrderStatus.PLACED) {
            throw new AgentOrderException(
                    "order " + orderId + " is " + current + "; only a PLACED or PAID order can be cancelled");
        }
        statuses.put(orderId, OrderStatus.CANCELLED);
        Payment refunded = new Payment(payments.get(orderId).id(), orderId, order.amount(), PaymentStatus.REFUNDED);
        payments.put(orderId, refunded);
        return refunded;
    }

    private void require(String orderId) {
        if (order == null || !order.id().equals(orderId)) {
            throw new AgentOrderException("order " + orderId + " does not exist");
        }
    }
}
