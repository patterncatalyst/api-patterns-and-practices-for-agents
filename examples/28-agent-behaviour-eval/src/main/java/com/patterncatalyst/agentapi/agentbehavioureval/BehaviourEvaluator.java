package com.patterncatalyst.agentapi.agentbehavioureval;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The deterministic gate this chapter is about: the same committed eval spec applied to the same
 * transcript always reaches the same verdict, independent of whether the transcript came from a
 * live model call or a committed fixture. It asserts only captured, structured signals, never
 * model prose: the tool names a recorded run actually called (never its argument literals, which
 * are free text and vary even on a compliant run), the call and argument-error budget, the token
 * budget, and the durable outcome (the order's final status, whether the refund actually
 * completed). Assertions are evaluated in order; the first one a transcript fails is the recorded
 * verdict's {@code violatedAssertion}.
 *
 * <p>{@code agent.eval.spec.path} (demo code, like 26-decision-rule-review's {@code
 * review.rule.path}) names the spec file read once at startup.
 */
@ApplicationScoped
public class BehaviourEvaluator {

    private static final Logger LOG = Logger.getLogger(BehaviourEvaluator.class);

    @ConfigProperty(name = "agent.eval.spec.path")
    String specPath;

    private EvalSpec spec;

    @PostConstruct
    void loadSpec() {
        try {
            String json = Files.readString(Path.of(specPath));
            spec = new ObjectMapper().readValue(json, EvalSpec.class);
            LOG.infof("eval spec loaded: evalId=%s path=%s expectedToolSequence=%s",
                    spec.evalId(), specPath, spec.expectedToolSequence());
        } catch (IOException e) {
            throw new IllegalStateException("could not read eval spec file " + specPath, e);
        }
    }

    public EvalVerdict evaluate(Transcript transcript) {
        return evaluate(spec, transcript);
    }

    /**
     * Package-visible so a unit-level test can score an arbitrary, for example mutated,
     * transcript against the real spec without going through CDI or a second configured bean.
     */
    static EvalVerdict evaluate(EvalSpec spec, Transcript transcript) {
        if (!onlyExpectedToolsWereCalled(spec, transcript)) {
            return EvalVerdict.fail("unexpected-tool-called");
        }
        if (transcript.toolCalls().size() > spec.maxToolCalls()) {
            return EvalVerdict.fail("call-budget-exceeded");
        }
        long argErrors = transcript.toolCalls().stream().filter(TranscriptToolCall::argError).count();
        if (argErrors > spec.maxArgErrors()) {
            return EvalVerdict.fail("arg-error-present");
        }
        if (transcript.totalTokens() > spec.maxTotalTokens()) {
            return EvalVerdict.fail("token-budget-exceeded");
        }
        if (transcript.refundCompleted() != spec.requireRefundCompleted()) {
            return EvalVerdict.fail("refund-not-completed");
        }
        if (!spec.requireFinalOrderStatus().equals(transcript.finalOrderStatus())) {
            return EvalVerdict.fail("final-status-mismatch");
        }
        return EvalVerdict.pass();
    }

    private static boolean onlyExpectedToolsWereCalled(EvalSpec spec, Transcript transcript) {
        return transcript.toolCalls().stream()
                .map(TranscriptToolCall::name)
                .allMatch(spec.expectedToolSequence()::contains);
    }
}
