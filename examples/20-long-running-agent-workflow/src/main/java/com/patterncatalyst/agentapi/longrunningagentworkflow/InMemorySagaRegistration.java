package com.patterncatalyst.agentapi.longrunningagentworkflow;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.saga.InMemorySagaService;

/**
 * Registers Camel's in-memory {@code CamelSagaService} with the {@code CamelContext}, the only
 * saga coordinator this example uses, in the live compose run and in {@code mvn -B verify} alike.
 * No external LRA coordinator and no Narayana: {@code org.apache.camel.saga.InMemorySagaService}
 * ships in {@code camel-support}, already on the classpath through {@code camel-quarkus-saga}, and
 * tracks every enlisted step's compensation and completion endpoint in process memory for the life
 * of this JVM. The event-driven-patterns-with-quarkus-and-camel sibling's chapter 23 registers the
 * same class, but only for its tests, because its live run holds an external Narayana LRA
 * coordinator instead; this chapter's owner-approved shape has no external coordinator at all, so
 * the registration here runs unconditionally.
 */
@ApplicationScoped
public class InMemorySagaRegistration extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        getContext().addService(new InMemorySagaService());
    }
}
