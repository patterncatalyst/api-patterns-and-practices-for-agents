package com.patterncatalyst.agentapi.whyagentapis;

import com.patterncatalyst.agentapi.Order;
import com.patterncatalyst.agentapi.OrderStatus;
import com.patterncatalyst.agentapi.Payment;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

/**
 * The bad contract: three granular, CRUD-ish tools that mirror a typical internal admin API.
 * The same cancel-and-refund task now needs a lookup, a status update and a separate refund
 * call, and every failure is a bare code with no remedy text, so a caller that does not already
 * know the shape must guess.
 */
@ApplicationScoped
public class BadOrderOpsTools {

    private static final Logger LOG = Logger.getLogger(BadOrderOpsTools.class);

    private final Counter calls;
    private final Counter argErrors;
    private final AtomicInteger runCalls = new AtomicInteger();
    private final AtomicInteger runArgErrors = new AtomicInteger();

    @Inject
    OrderOpsService service;

    @Inject
    public BadOrderOpsTools(MeterRegistry registry) {
        this.calls = Counter.builder("whyagentapis.tool.calls")
                .tag("contract", "bad")
                .description("Number of times a tool was called for the bad contract run")
                .register(registry);
        this.argErrors = Counter.builder("whyagentapis.tool.arg_errors")
                .tag("contract", "bad")
                .description("Number of tool calls that failed validation for the bad contract run")
                .register(registry);
    }

    /** Resets the per-run counters. Called once before each experiment run. */
    public void resetRun() {
        runCalls.set(0);
        runArgErrors.set(0);
    }

    public int runCalls() {
        return runCalls.get();
    }

    public int runArgErrors() {
        return runArgErrors.get();
    }

    private String opaqueError(OrderOpsException e, String toolName, String orderId) {
        argErrors.increment();
        runArgErrors.incrementAndGet();
        LOG.infof("bad tool arg error: %s orderId=%s code=%s", toolName, orderId, e.code());
        return "{\"error\":\"" + e.code().opaque() + "\"}";
    }

    @Tool("Look up a shipping order's raw record by id. Returns its status and amount.")
    public String getOrder(@P("the order id, for example O-1007") String orderId) {
        calls.increment();
        runCalls.incrementAndGet();
        LOG.infof("bad tool call: getOrder orderId=%s", orderId);
        try {
            Order order = service.order(orderId);
            OrderStatus status = service.status(orderId);
            return String.format(Locale.ROOT, "{\"id\":\"%s\",\"status\":\"%s\",\"amount\":%s}",
                    order.id(), status, order.amount());
        } catch (OrderOpsException e) {
            return opaqueError(e, "getOrder", orderId);
        }
    }

    @Tool("""
            Update a shipping order's status by id. status must be exactly one of PLACED, PAID,
            SHIPPED, DELIVERED, CANCELLED.
            """)
    public String updateOrderStatus(
            @P("the order id, for example O-1007") String orderId,
            @P("the new status: PLACED, PAID, SHIPPED, DELIVERED or CANCELLED") String status) {
        calls.increment();
        runCalls.incrementAndGet();
        LOG.infof("bad tool call: updateOrderStatus orderId=%s status=%s", orderId, status);
        OrderStatus next;
        try {
            next = OrderStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            argErrors.increment();
            runArgErrors.incrementAndGet();
            LOG.infof("bad tool arg error: updateOrderStatus orderId=%s invalid status=%s", orderId, status);
            return "{\"error\":\"E400\"}";
        }
        try {
            service.updateStatus(orderId, next);
            return "{\"status\":\"" + next + "\"}";
        } catch (OrderOpsException e) {
            return opaqueError(e, "updateOrderStatus", orderId);
        }
    }

    @Tool("Create a refund for a shipping order by id and amount. amount must exactly match the order's amount.")
    public String createRefund(
            @P("the order id, for example O-1007") String orderId,
            @P("the refund amount, must match the order's amount exactly") double amount) {
        calls.increment();
        runCalls.incrementAndGet();
        LOG.infof("bad tool call: createRefund orderId=%s amount=%s", orderId, amount);
        try {
            BigDecimal given = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
            Payment refund = service.refund(orderId, given);
            return "{\"refundId\":\"" + refund.id() + "\",\"status\":\"REFUNDED\"}";
        } catch (OrderOpsException e) {
            return opaqueError(e, "createRefund", orderId);
        }
    }
}
