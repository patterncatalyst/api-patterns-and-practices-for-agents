package com.patterncatalyst.agentapi.structuredoutputs;

import dev.langchain4j.service.SystemMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The UNTYPED path: the same system message, the same unnamed Ollama chat model, the same
 * temperature-0 setting as {@link TypedIntentExtractor}, but a bare {@code String} return type.
 * Nothing in this interface derives a schema or sends an Ollama {@code format}, so the model's
 * output shape rests entirely on how well the system message's wording is followed, call to call.
 * {@link IntentExtractionBean} attempts the same strict parse into {@link ExtractedIntent} against
 * whatever text comes back, which is the downstream witness this appendix's negative case relies
 * on.
 */
@RegisterAiService
@ApplicationScoped
public interface UntypedIntentExtractor {

    @SystemMessage(ExtractionPrompt.SYSTEM_MESSAGE)
    String extract(String message);
}
