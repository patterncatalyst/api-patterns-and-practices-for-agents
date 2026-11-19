package com.patterncatalyst.agentapi.agentbehavioureval;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The thin bridge each {@link EvalRoute} trigger calls: score the transcript against the
 * committed eval spec, then record the verdict. By the time a transcript reaches here, whatever
 * produced it, a live model call or a replayed fixture, is already behind it, so this bridge
 * needs no {@code @ActivateRequestContext}: there is no request-scoped langchain4j proxy to
 * activate a context for, unlike {@link LiveRunner}.
 */
@ApplicationScoped
public class EvalBean {

    @Inject
    BehaviourEvaluator evaluator;

    @Inject
    EvalRecorder recorder;

    public EvalVerdict evaluateAndRecord(Transcript transcript) {
        EvalVerdict verdict = evaluator.evaluate(transcript);
        recorder.record(transcript, verdict);
        return verdict;
    }
}
