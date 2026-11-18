package com.patterncatalyst.agentapi.stackcheck;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

/**
 * Thin CDI bridge so the Camel route can call the langchain4j AiService. The
 * generated {@code OrderAssistant} proxy is request-scoped, but a Camel platform-http
 * route runs on a Vert.x worker thread with no request context active, so this method
 * activates one for the call.
 */
@ApplicationScoped
public class AssistantBean {

    @Inject
    OrderAssistant assistant;

    @ActivateRequestContext
    public String ask(String question) {
        return assistant.chat(question);
    }
}
