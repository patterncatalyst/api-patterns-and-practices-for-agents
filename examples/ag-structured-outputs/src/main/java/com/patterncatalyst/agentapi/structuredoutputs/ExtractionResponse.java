package com.patterncatalyst.agentapi.structuredoutputs;

/**
 * The one response shape both endpoints return. {@code structuredOutputEnabled} names which
 * mechanism produced {@code raw}: {@code true} means Ollama's format grammar constrained
 * generation before any text existed to parse; {@code false} means nothing did, and {@code
 * parseOk} reports what a plain, strict Jackson parse made of whatever text came back.
 * {@code parsed} is non-null only when {@code parseOk} is true.
 */
public record ExtractionResponse(
        String path,
        boolean structuredOutputEnabled,
        boolean parseOk,
        ExtractedIntent parsed,
        String raw) {
}
