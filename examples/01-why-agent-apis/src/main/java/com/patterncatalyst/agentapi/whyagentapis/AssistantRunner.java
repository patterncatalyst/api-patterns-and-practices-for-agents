package com.patterncatalyst.agentapi.whyagentapis;

import com.patterncatalyst.agentapi.OrderStatus;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Runs the one cancel-and-refund task against each contract and reports what it cost: tool
 * calls, argument errors and token usage. LIVE mode only (see README); replay never calls this.
 *
 * <p>The generated AiService proxies are request-scoped, but {@link RunResource} runs this on a
 * worker thread with no request context guaranteed active, so each run method activates one
 * (the same fix as the stack-check example's {@code AssistantBean}).
 */
@ApplicationScoped
public class AssistantRunner {

    private static final Logger LOG = Logger.getLogger(AssistantRunner.class);

    @Inject
    GoodContractAssistant goodAssistant;

    @Inject
    BadContractAssistant badAssistant;

    @Inject
    GoodOrderOpsTools goodTools;

    @Inject
    BadOrderOpsTools badTools;

    @Inject
    OrderOpsService service;

    @ActivateRequestContext
    public RunOutcome runGood() {
        service.reset();
        goodTools.resetRun();
        Result<String> result = goodAssistant.run(Prompts.CANCEL_AND_REFUND_TASK);
        return outcome("good", result, goodTools.runCalls(), goodTools.runArgErrors());
    }

    @ActivateRequestContext
    public RunOutcome runBad() {
        service.reset();
        badTools.resetRun();
        Result<String> result = badAssistant.run(Prompts.CANCEL_AND_REFUND_TASK);
        return outcome("bad", result, badTools.runCalls(), badTools.runArgErrors());
    }

    private RunOutcome outcome(String contract, Result<String> result, int toolCalls, int argErrors) {
        TokenUsage usage = result.tokenUsage();
        int in = usage != null && usage.inputTokenCount() != null ? usage.inputTokenCount() : 0;
        int out = usage != null && usage.outputTokenCount() != null ? usage.outputTokenCount() : 0;
        int total = usage != null && usage.totalTokenCount() != null ? usage.totalTokenCount() : in + out;
        List<String> trace = result.toolExecutions().stream()
                .map(te -> te.request().name() + "(" + te.request().arguments() + ") -> " + te.result())
                .toList();
        OrderStatus finalStatus = service.status(OrderOpsService.SEED_ORDER_ID);
        boolean refunded = service.isRefunded(OrderOpsService.SEED_ORDER_ID);
        LOG.infof("%s contract run: toolCalls=%d argErrors=%d inputTokens=%d outputTokens=%d totalTokens=%d "
                        + "finalStatus=%s refundCompleted=%s",
                contract, toolCalls, argErrors, in, out, total, finalStatus, refunded);
        return new RunOutcome(contract, Prompts.CANCEL_AND_REFUND_TASK, toolCalls, argErrors,
                in, out, total, finalStatus.name(), refunded, trace, result.content());
    }
}
