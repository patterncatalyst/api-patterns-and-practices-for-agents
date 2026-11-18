package com.patterncatalyst.agentapi.whyagentapis;

import com.patterncatalyst.agentapi.Payment;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

/**
 * The good contract: one intent tool with a strict, two-field schema and an instructive error.
 * Cancelling and refunding is always a single call, whether it succeeds or fails.
 */
@ApplicationScoped
public class GoodOrderOpsTools {

    private static final Logger LOG = Logger.getLogger(GoodOrderOpsTools.class);

    private final Counter calls;
    private final Counter argErrors;
    private final AtomicInteger runCalls = new AtomicInteger();
    private final AtomicInteger runArgErrors = new AtomicInteger();

    @Inject
    OrderOpsService service;

    @Inject
    public GoodOrderOpsTools(MeterRegistry registry) {
        this.calls = Counter.builder("whyagentapis.tool.calls")
                .tag("contract", "good")
                .description("Number of times a tool was called for the good contract run")
                .register(registry);
        this.argErrors = Counter.builder("whyagentapis.tool.arg_errors")
                .tag("contract", "good")
                .description("Number of tool calls that failed validation for the good contract run")
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

    @Tool("""
            Cancel a shipping order and refund the customer in a single step. Use this whenever
            asked to cancel an order and refund the customer; it looks up the order, checks it
            can be cancelled, and issues the refund itself. If it cannot complete, it returns a
            message stating exactly what is wrong and what to check next.
            """)
    public String cancelAndRefund(
            @P("the order id, for example O-1007") String orderId,
            @P("a short free-text reason for the cancellation") String reason) {
        calls.increment();
        runCalls.incrementAndGet();
        LOG.infof("good tool call: cancelAndRefund orderId=%s reason=%s", orderId, reason);
        try {
            Payment refund = service.cancelAndRefund(orderId, reason);
            return "Order " + orderId + " is cancelled and " + refund.amount()
                    + " has been refunded to the original payment method.";
        } catch (OrderOpsException e) {
            argErrors.increment();
            runArgErrors.incrementAndGet();
            LOG.infof("good tool arg error: cancelAndRefund orderId=%s code=%s", orderId, e.code());
            return "error: " + e.getMessage();
        }
    }
}
