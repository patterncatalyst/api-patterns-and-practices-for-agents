package com.patterncatalyst.agentapi.structuredoutputs;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.guardrail.OutputGuardrails;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The TYPED path. The only difference from {@link UntypedIntentExtractor} is this method's
 * declared return type: {@code quarkus-langchain4j-ollama} derives a JSON Schema from
 * {@link ExtractedIntent} and sends it as this request's Ollama {@code format}, so llama.cpp's
 * grammar-constrained decoding cannot emit a token sequence that does not already match the
 * record's shape, on every call, regardless of what the model's own token-level choices are that
 * run. {@link IntentJsonGuardrail} is a second, weaker safety net behind that: a
 * retry-on-parse-failure loop (up to {@code quarkus.langchain4j.guardrails.max-retries}) for the
 * rare response the grammar still lets through malformed; a hard failure after retries are
 * exhausted throws {@code dev.langchain4j.guardrail.OutputGuardrailException}. Exactly one output
 * guardrail is attached here: chaining more than one on a langchain4j core 1.19.x/1.20.x line
 * before 1.20.2 is a known way to silently disable the retry (see the README).
 */
@RegisterAiService
@ApplicationScoped
public interface TypedIntentExtractor {

    @SystemMessage(ExtractionPrompt.SYSTEM_MESSAGE)
    @OutputGuardrails(IntentJsonGuardrail.class)
    ExtractedIntent extract(String message);
}
