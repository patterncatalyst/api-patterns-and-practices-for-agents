package com.patterncatalyst.agentapi.multiagentchoreography.order;

import com.patterncatalyst.agentapi.Order;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.apache.camel.ProducerTemplate;
import org.jboss.logging.Logger;

/**
 * The order agent's one tool, whichever decision path called it ({@link OrderAdvisor} over
 * Ollama, or {@link OrderFixtureAdvisor} under MODEL=off). It writes the durable {@code
 * orders.orders} record, increments a counter, logs a line, and publishes {@code
 * apa.orders.placed} so the shipping agent reacts to the event on its own, with no method call
 * from this package into {@code shipping}.
 */
@ApplicationScoped
public class OrderPlacementTool {

    private static final Logger LOG = Logger.getLogger(OrderPlacementTool.class);

    private final Counter invocations;
    private final DataSource dataSource;
    private final ProducerTemplate producer;

    @Inject
    public OrderPlacementTool(MeterRegistry registry, DataSource dataSource, ProducerTemplate producer) {
        this.invocations = Counter.builder("choreography.order.tool.invocations")
                .description("Number of times the placeOrder tool recorded and published an order")
                .register(registry);
        this.dataSource = dataSource;
        this.producer = producer;
    }

    @Tool("Record a placed order and publish OrderPlaced for the shipping agent to react to")
    @Transactional
    public String placeOrder(
            @P("the order id, for example O-9001") String orderId,
            @P("the customer id") String customerId,
            @P("the item sku") String itemSku,
            @P("the quantity, a whole number") String quantity,
            @P("the amount, a decimal string, for example 49.99") String amount) {
        invocations.increment();
        Order order = new Order(orderId, customerId, itemSku, Integer.parseInt(quantity), new BigDecimal(amount));
        LOG.infof("tool invoked: placeOrder orderId=%s customerId=%s", order.id(), order.customerId());
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount) "
                            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING")) {
                insert.setString(1, order.id());
                insert.setString(2, order.customerId());
                insert.setString(3, order.itemSku());
                insert.setInt(4, order.quantity());
                insert.setBigDecimal(5, order.amount());
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("order record failed for " + order.id(), e);
        }
        producer.sendBody("direct:publishOrderPlaced", order);
        return "order placed and published for " + order.id();
    }
}
