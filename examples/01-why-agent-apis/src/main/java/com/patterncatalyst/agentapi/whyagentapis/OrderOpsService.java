package com.patterncatalyst.agentapi.whyagentapis;

import com.patterncatalyst.agentapi.Order;
import com.patterncatalyst.agentapi.OrderStatus;
import com.patterncatalyst.agentapi.Payment;
import com.patterncatalyst.agentapi.PaymentStatus;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * The one business operation both contracts drive: cancel a shipping order and refund the
 * customer. Holds the seeded order {@code O-1007} in memory; both {@link GoodOrderOpsTools}
 * and {@link BadOrderOpsTools} call this same service, so the measured contrast between the
 * two contracts comes only from the tool shape, never from a difference in business logic.
 */
@ApplicationScoped
public class OrderOpsService {

    private static final Logger LOG = Logger.getLogger(OrderOpsService.class);
    static final String SEED_ORDER_ID = "O-1007";

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, OrderStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        reset();
    }

    /** Restores the seeded order to its starting state, so a run always starts the same way. */
    public synchronized void reset() {
        orders.clear();
        statuses.clear();
        payments.clear();
        Order order = new Order(SEED_ORDER_ID, "CUST-42", "SKU-9", 1, new BigDecimal("129.99"));
        orders.put(order.id(), order);
        statuses.put(order.id(), OrderStatus.PAID);
        payments.put(order.id(), new Payment("PAY-7001", order.id(), order.amount(), PaymentStatus.COMPLETED));
    }

    public Order order(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new OrderOpsException(OrderOpsException.Code.MISSING_FIELD, "orderId is required");
        }
        Order order = orders.get(orderId);
        if (order == null) {
            throw new OrderOpsException(OrderOpsException.Code.NOT_FOUND, "order " + orderId + " does not exist");
        }
        return order;
    }

    public OrderStatus status(String orderId) {
        order(orderId);
        return statuses.get(orderId);
    }

    /** Whether the order's payment has actually reached REFUNDED, not just whether the order was cancelled. */
    public boolean isRefunded(String orderId) {
        order(orderId);
        Payment payment = payments.get(orderId);
        return payment != null && payment.status() == PaymentStatus.REFUNDED;
    }

    public synchronized void updateStatus(String orderId, OrderStatus next) {
        OrderStatus current = status(orderId);
        boolean cancellable = current == OrderStatus.PLACED || current == OrderStatus.PAID;
        if (next != OrderStatus.CANCELLED || !cancellable) {
            throw new OrderOpsException(OrderOpsException.Code.INVALID_TRANSITION,
                    "order " + orderId + " is " + current + "; only a PLACED or PAID order can move to CANCELLED");
        }
        statuses.put(orderId, next);
    }

    public synchronized Payment refund(String orderId, BigDecimal amount) {
        Order order = order(orderId);
        OrderStatus current = statuses.get(orderId);
        if (current != OrderStatus.CANCELLED) {
            throw new OrderOpsException(OrderOpsException.Code.NOT_CANCELLED,
                    "order " + orderId + " is " + current + "; cancel it before refunding");
        }
        Payment existing = payments.get(orderId);
        if (existing != null && existing.status() == PaymentStatus.REFUNDED) {
            throw new OrderOpsException(OrderOpsException.Code.ALREADY_REFUNDED,
                    "order " + orderId + " was already refunded");
        }
        BigDecimal expected = order.amount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal given = amount.setScale(2, RoundingMode.HALF_UP);
        if (expected.compareTo(given) != 0) {
            throw new OrderOpsException(OrderOpsException.Code.AMOUNT_MISMATCH,
                    "refund amount " + given + " does not match order amount " + expected);
        }
        Payment refunded = new Payment(existing != null ? existing.id() : "PAY-" + orderId, orderId, given, PaymentStatus.REFUNDED);
        payments.put(orderId, refunded);
        return refunded;
    }

    /** The good contract's single operation: the same two steps above, done atomically. */
    public synchronized Payment cancelAndRefund(String orderId, String reason) {
        Order order = order(orderId);
        updateStatus(orderId, OrderStatus.CANCELLED);
        Payment refunded = refund(orderId, order.amount());
        LOG.infof("cancelAndRefund orderId=%s reason=%s refundId=%s", orderId, reason, refunded.id());
        return refunded;
    }
}
