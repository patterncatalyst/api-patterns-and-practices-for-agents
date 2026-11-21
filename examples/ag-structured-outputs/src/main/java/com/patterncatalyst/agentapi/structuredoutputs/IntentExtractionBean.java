package com.patterncatalyst.agentapi.structuredoutputs;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.langchain4j.guardrail.OutputGuardrailException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Routes both endpoints and holds the one downstream parse every path other than the live typed
 * one must survive on its own. {@code agent.extraction.model-source} picks ollama (the default,
 * live) or fixture (forced by the {@code %test} profile, MODEL=off). {@code
 * agent.extraction.structured-output-enabled} is the negative witness' own switch: {@code true}
 * (the default) calls {@link TypedIntentExtractor}, whose own declared return type gets Ollama's
 * format grammar; {@code false} calls {@link UntypedIntentExtractor} instead, the exact same
 * unconstrained path {@code /extract/untyped} always uses, and runs its result through the same
 * strict parse {@code /extract/untyped} runs, so flipping one flag is enough to show
 * {@code /extract/typed} itself produce a response that fails to parse.
 */
@ApplicationScoped
public class IntentExtractionBean {

    private static final ObjectMapper STRICT = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .build();

    @Inject
    TypedIntentExtractor typedExtractor;

    @Inject
    UntypedIntentExtractor untypedExtractor;

    @Inject
    FixtureIntentSource fixture;

    @ConfigProperty(name = "agent.extraction.model-source", defaultValue = "ollama")
    String modelSource;

    @ConfigProperty(name = "agent.extraction.structured-output-enabled", defaultValue = "true")
    boolean structuredOutputEnabled;

    public ExtractionResponse extractTyped(String message) {
        boolean fixtureMode = "fixture".equals(modelSource);
        if (!structuredOutputEnabled) {
            // The negative witness: the schema constraint turned off for this same endpoint, no
            // AiServices-derived parse to fall back on, only the plain strict parse below.
            String raw = fixtureMode ? fixture.untypedRaw(message) : untypedExtractor.extract(message);
            return parse("typed", false, raw);
        }
        if (fixtureMode) {
            return parse("typed", true, fixture.typedRaw(message));
        }
        try {
            ExtractedIntent parsed = typedExtractor.extract(message);
            return new ExtractionResponse("typed", true, true, parsed, null);
        } catch (OutputGuardrailException e) {
            return new ExtractionResponse("typed", true, false, null, e.getMessage());
        }
    }

    public ExtractionResponse extractUntyped(String message) {
        boolean fixtureMode = "fixture".equals(modelSource);
        String raw = fixtureMode ? fixture.untypedRaw(message) : untypedExtractor.extract(message);
        return parse("untyped", structuredOutputEnabled, raw);
    }

    private ExtractionResponse parse(String path, boolean structuredOutputEnabled, String raw) {
        try {
            ExtractedIntent parsed = STRICT.readValue(raw, ExtractedIntent.class);
            return new ExtractionResponse(path, structuredOutputEnabled, true, parsed, raw);
        } catch (Exception e) {
            return new ExtractionResponse(path, structuredOutputEnabled, false, null, raw);
        }
    }
}
