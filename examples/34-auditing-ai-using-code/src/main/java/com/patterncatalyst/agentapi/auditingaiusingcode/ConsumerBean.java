package com.patterncatalyst.agentapi.auditingaiusingcode;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Thin CDI bridge so the Camel platform-http route ({@link TriggerRoute}) can drive either decision
 * path. {@code agent.outbound.model-source} picks it: {@code ollama} (the default, and what compose
 * and the live {@code verify.sh} always use) lets the real model decide to call
 * {@link AuditedToolCaller#orderStatus(String)} itself; {@code fixture} (forced by the {@code %test}
 * profile, MODEL=off) calls that SAME method directly, with no model involved, so Dev Services tests
 * and CI never need Ollama. Either path ends at the same audited call, so the audit record this
 * chapter's checks assert on is appended identically whichever path produced it.
 *
 * <p>A Camel platform-http route runs on a Vert.x worker thread with no request context active, and
 * the generated {@link OutboundCallAgent} proxy is request-scoped, so this method activates one for
 * the call, the same bridge 00-stack-check's {@code AssistantBean} and
 * 24-langchain4j-programmatic-consumer's {@code ConsumerBean} use.
 */
@ApplicationScoped
public class ConsumerBean {

    @Inject
    OutboundCallAgent agent;

    @Inject
    AuditedToolCaller auditedToolCaller;

    @Inject
    OutboundCallResultRecorder recorder;

    @ConfigProperty(name = "agent.outbound.model-source", defaultValue = "ollama")
    String modelSource;

    @ActivateRequestContext
    public void consume(String orderId) {
        String orderStatusResult;
        if ("fixture".equals(modelSource)) {
            orderStatusResult = auditedToolCaller.lookupOrderStatus(orderId);
        } else {
            // The model's only job is to call lookupOrderStatus. What gets recorded is the text
            // that call actually returned, read from Result.toolExecutions(), not the model's
            // own prose: a nondeterministic model cannot change the recorded status or tracking
            // number as long as it makes the call.
            Result<String> result = agent.consume("Get the status of order " + orderId + ".");
            orderStatusResult = result.toolExecutions().stream()
                    .filter(execution -> "lookupOrderStatus".equals(execution.request().name()))
                    .map(ToolExecution::result)
                    .reduce((first, last) -> last)
                    .orElse("");
        }
        recorder.recordConsultation(orderId, orderStatusResult);
    }
}
