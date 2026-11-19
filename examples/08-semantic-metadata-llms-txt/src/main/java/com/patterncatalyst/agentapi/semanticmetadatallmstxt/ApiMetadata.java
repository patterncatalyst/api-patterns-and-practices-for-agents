package com.patterncatalyst.agentapi.semanticmetadatallmstxt;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The single source of operation metadata for this service. {@link LlmsTxtResource} and
 * {@link PortalResource} both read {@link #title()}, {@link #summary()} and
 * {@link #operations()} from this one bean and render two different formats from it; neither
 * resource hardcodes its own operation list. A caller that wants to confirm the two surfaces
 * describe the same API never has to trust that two independent write-ups were kept in sync by
 * hand, there is only one write-up.
 */
@ApplicationScoped
public class ApiMetadata {

    public String title() {
        return "Order Service";
    }

    public String summary() {
        return "Agent-facing order service: place an order and look one up by id.";
    }

    public List<OperationInfo> operations() {
        return List.of(
                new OperationInfo("placeOrder", "POST", "/orders", "Place a new order."),
                new OperationInfo("getOrder", "GET", "/orders/{id}", "Look up an order by id."));
    }
}
