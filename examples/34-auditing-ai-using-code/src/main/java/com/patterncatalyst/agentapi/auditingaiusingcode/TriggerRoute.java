package com.patterncatalyst.agentapi.auditingaiusingcode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/**
 * The trigger: {@code POST /consume} with the order id as the plain-text body, the same Camel
 * platform-http convention 00-stack-check's {@code AssistantRoute} and
 * 24-langchain4j-programmatic-consumer's {@code ConsumeOrderStatusRoute} use for an HTTP-triggered
 * direct-langchain4j call. No REST framework.
 */
@ApplicationScoped
public class TriggerRoute extends RouteBuilder {

    @Inject
    ConsumerBean consumerBean;

    @Override
    public void configure() {
        from("platform-http:/consume?httpMethodRestrict=POST")
                .routeId("trigger-consume")
                .convertBodyTo(String.class)
                .process(exchange -> {
                    String orderId = exchange.getIn().getBody(String.class).strip();
                    consumerBean.consume(orderId);
                    exchange.getMessage().setBody("accepted: " + orderId);
                })
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"));
    }
}
