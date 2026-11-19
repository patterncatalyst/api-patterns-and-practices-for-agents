package com.patterncatalyst.agentapi.decisionrulereview;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The thin bridge the Camel platform-http route calls: evaluate the proposal against the
 * committed decision rule, then record the verdict. No model is ever on this path, so unlike
 * ch24's {@code ConsumerBean} and ch25's {@code NotificationBean}, this bridge needs no
 * {@code @ActivateRequestContext}: there is no request-scoped langchain4j {@code AiService} proxy
 * to activate a context for, since the reviewer this chapter is about is deterministic by
 * construction.
 */
@ApplicationScoped
public class ReviewBean {

    @Inject
    DecisionRuleReviewer reviewer;

    @Inject
    ReviewRecorder recorder;

    public Verdict review(RefundProposalRun run) {
        Verdict verdict = reviewer.evaluate(run);
        recorder.record(run, verdict);
        return verdict;
    }
}
