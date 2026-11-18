package com.patterncatalyst.agentapi.stackcheck;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;

/** Lets a caller ask the local assistant a question, exercising the Ollama tool-call path. */
@ApplicationScoped
public class AssistantRoute extends RouteBuilder {

    @Inject
    AssistantBean assistantBean;

    @Override
    public void configure() {
        from("platform-http:/assistant?httpMethodRestrict=POST")
                .routeId("assistant-ask")
                .convertBodyTo(String.class)
                .process(exchange -> {
                    String question = exchange.getIn().getBody(String.class);
                    exchange.getMessage().setBody(assistantBean.ask(question));
                })
                .setHeader(Exchange.CONTENT_TYPE, constant("text/plain"));
    }
}
