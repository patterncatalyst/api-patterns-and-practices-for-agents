package com.patterncatalyst.agentapi.idempotencysaferetries;

import com.patterncatalyst.agentapi.Topics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.processor.idempotent.jdbc.JdbcMessageIdRepository;

/**
 * The event/EIP edge: the Idempotent Consumer EIP, backed by a JDBC idempotent repository
 * (camel-quarkus-sql's {@link JdbcMessageIdRepository}), keyed on the caller's
 * Idempotency-Key header. A first-time key runs {@link PlaceOrderSaga#placeNewOrder} and
 * publishes OrderPlaced once; a repeat key never runs the saga again, it replays the first
 * stored response through {@link PlaceOrderSaga#replayOrReject}. The agent-facing HTTP surface
 * is Quarkus REST ({@link OrderResource}), which invokes this route through a {@code direct:}
 * endpoint; Camel never terminates an HTTP request directly in this book from r03 onward.
 *
 * <p>{@code skipDuplicate(false)} keeps duplicates flowing through the route (instead of being
 * silently dropped) so this route can branch on {@link Exchange#DUPLICATE_MESSAGE} and reply
 * with the right body, rather than no body at all. The repository defaults (eager marking,
 * remove-on-failure) mean a request that fails validation never permanently locks its key: a
 * genuine retry with a corrected body is free to try again.
 */
@ApplicationScoped
public class PlaceOrderRoute extends RouteBuilder {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    @Inject
    PlaceOrderSaga saga;

    @Inject
    DataSource dataSource;

    @Override
    public void configure() {
        JdbcMessageIdRepository repository = new JdbcMessageIdRepository(dataSource, "place-order");
        // Schema-qualified, like every other table in this book: application.properties sets no
        // currentSchema/search_path, so Dev Services and compose see the same statement either
        // way. Flyway pre-creates this table (V1__orders_and_idempotency.sql); the repository's
        // own create-if-missing check then finds it already there and does nothing further.
        repository.setTableName("orders.idempotency_consumer");

        from("direct:place-order")
                .routeId("place-order")
                .idempotentConsumer(header(IDEMPOTENCY_KEY_HEADER), repository)
                .skipDuplicate(false)
                .choice()
                .when(exchangeProperty(Exchange.DUPLICATE_MESSAGE).isEqualTo(Boolean.TRUE))
                .process(exchange -> exchange.getMessage().setBody(saga.replayOrReject(
                        exchange.getIn().getHeader(IDEMPOTENCY_KEY_HEADER, String.class),
                        exchange.getIn().getBody(OrderCreateRequest.class))))
                .otherwise()
                .process(exchange -> exchange.getMessage().setBody(saga.placeNewOrder(
                        exchange.getIn().getHeader(IDEMPOTENCY_KEY_HEADER, String.class),
                        exchange.getIn().getBody(OrderCreateRequest.class))))
                .setHeader(KafkaConstants.KEY, simple("${body.event.orderId}"))
                .setHeader("apaPlaceOrderResult", body())
                .setBody(simple("${body.event}"))
                .marshal().json()
                .to("kafka:" + Topics.ORDERS_PLACED)
                .setBody(header("apaPlaceOrderResult"))
                .end()
                .end();
    }
}
