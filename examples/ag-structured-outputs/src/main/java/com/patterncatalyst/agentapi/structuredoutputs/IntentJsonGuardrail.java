package com.patterncatalyst.agentapi.structuredoutputs;

import dev.langchain4j.guardrail.JsonExtractorOutputGuardrail;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * A CDI-managed guardrail bean for {@link TypedIntentExtractor}. {@code
 * dev.langchain4j.guardrail.JsonExtractorOutputGuardrail} has no no-arg constructor (it always
 * needs the target class or a {@code TypeReference}), so Quarkus's AiServices validator cannot
 * instantiate the stock class as a bean directly; this subclass fixes the target type to
 * {@link ExtractedIntent} and exposes the no-arg constructor {@code @OutputGuardrails} needs to
 * find a bean for.
 */
@ApplicationScoped
public class IntentJsonGuardrail extends JsonExtractorOutputGuardrail<ExtractedIntent> {

    public IntentJsonGuardrail() {
        super(ExtractedIntent.class);
    }
}
