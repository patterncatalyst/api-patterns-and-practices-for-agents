package com.patterncatalyst.agentapi.stackcheck;

import dev.langchain4j.agent.tool.Tool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * A local tool the {@link OrderAssistant} may call. qwen3's tool calling is
 * nondeterministic, so the round trip is asserted by this invocation being
 * observable (the counter and the log line below), never by the model's prose.
 */
@ApplicationScoped
public class OrderStatusTool {

    private static final Logger LOG = Logger.getLogger(OrderStatusTool.class);

    private final Counter invocations;

    @Inject
    public OrderStatusTool(MeterRegistry registry) {
        this.invocations = Counter.builder("stackcheck.tool.invocations")
                .description("Number of times the orderStatus tool was called by the assistant")
                .register(registry);
    }

    @Tool("Look up the status of a shipping order by its order id")
    public String orderStatus(String orderId) {
        invocations.increment();
        LOG.infof("tool invoked: orderStatus orderId=%s", orderId);
        // Seeded status: this example proves the tool-call round trip, not a real lookup.
        return "Order " + orderId + " is SCHEDULED with FASTLINE";
    }
}
