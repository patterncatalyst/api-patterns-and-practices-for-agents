package com.patterncatalyst.agentapi.longrunningagentworkflow;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.SagaPropagation;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The order-fulfillment saga: reserve stock, take payment, book a carrier (the agent step), each
 * wrapped in its own nested Camel Saga EIP block so each can declare its own
 * {@code .compensation(...)}. The outer {@code direct:fulfillOrder} block owns the saga's lifecycle
 * (propagation {@code REQUIRES_NEW}, the {@code fulfillment.saga.timeout}); each inner block joins
 * it with propagation {@code MANDATORY} and enlists its own compensation endpoint with the shared
 * in-memory coordinator {@link InMemorySagaRegistration} registers.
 *
 * <p>Completion mode is left at its default, {@code AUTO}: if every step's bean call returns
 * normally the saga completes (nothing to undo); if any step throws, the exception propagates back
 * through the {@code direct:} call chain to the owning {@code .saga()} block, which compensates
 * every step enlisted so far, in reverse order, instead of completing. A payment declined by
 * {@link FulfillmentService#takePayment(String)} is the only failure this example's verify.sh
 * drives, so only {@code releaseStock} actually runs there; {@code refundPayment} and
 * {@code cancelBooking} exist for the same reason the sibling's saga compensates every step it
 * enlists, and are exercised directly, for idempotency, by {@code OrderFulfillmentSagaTest}.
 */
@ApplicationScoped
public class OrderFulfillmentSagaRoute extends RouteBuilder {

    @Inject
    FulfillmentService service;

    @Inject
    CarrierDecisionBean carrierDecisionBean;

    @ConfigProperty(name = "fulfillment.saga.timeout")
    String timeout;

    @Override
    public void configure() {
        from("direct:fulfillOrder")
                .routeId("order-fulfillment-saga")
                .saga()
                    .propagation(SagaPropagation.REQUIRES_NEW)
                    .timeout(Duration.parse(timeout))
                    .option("orderId", simple("${header.orderId}"))
                .to("direct:reserveStock")
                .to("direct:takePayment")
                .to("direct:bookCarrier")
                .end();

        from("direct:reserveStock")
                .routeId("reserve-stock-step")
                .saga()
                    .propagation(SagaPropagation.MANDATORY)
                    .compensation("direct:releaseStock")
                    .option("orderId", simple("${header.orderId}"))
                .bean(service, "reserveStock(${header.orderId}, ${body})")
                .end();

        from("direct:releaseStock")
                .routeId("release-stock-compensation")
                .bean(service, "releaseStock(${header.orderId})");

        from("direct:takePayment")
                .routeId("take-payment-step")
                .saga()
                    .propagation(SagaPropagation.MANDATORY)
                    .compensation("direct:refundPayment")
                    .option("orderId", simple("${header.orderId}"))
                .bean(service, "takePayment(${header.orderId})")
                .end();

        from("direct:refundPayment")
                .routeId("refund-payment-compensation")
                .bean(service, "refundPayment(${header.orderId})");

        from("direct:bookCarrier")
                .routeId("book-carrier-step")
                .saga()
                    .propagation(SagaPropagation.MANDATORY)
                    .compensation("direct:cancelBooking")
                    .option("orderId", simple("${header.orderId}"))
                .bean(carrierDecisionBean, "bookCarrier(${header.orderId})")
                .end();

        from("direct:cancelBooking")
                .routeId("cancel-booking-compensation")
                .bean(service, "cancelBooking(${header.orderId})");
    }
}
