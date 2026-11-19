package com.patterncatalyst.agentapi.eventtriggeredagentwakeup;

import com.patterncatalyst.agentapi.Payment;
import com.patterncatalyst.agentapi.Topics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The trigger: a Kafka consumer on {@code apa.payments.processed} wakes the shipping agent the
 * instant a payment is processed, instead of a dashboard polling the same topic for the same
 * fact. {@code wakeup.consumer.enabled=false} (demo code, the negative witness in verify.sh)
 * stops this route entirely, so an event published while it is down sits on the topic unconsumed
 * until the route is restarted and reads it from where it left off.
 */
@ApplicationScoped
public class PaymentWakeupRoute extends RouteBuilder {

    @Inject
    AgentWakeupBean wakeupBean;

    @ConfigProperty(name = "wakeup.consumer.enabled", defaultValue = "true")
    boolean consumerEnabled;

    @Override
    public void configure() {
        from("kafka:" + Topics.PAYMENTS_PROCESSED + "?groupId={{wakeup.consumer.group-id}}")
                .routeId("payment-wakeup")
                .autoStartup(consumerEnabled)
                .unmarshal().json(Payment.class)
                .process(exchange -> wakeupBean.wake(exchange.getIn().getBody(Payment.class)));
    }
}
