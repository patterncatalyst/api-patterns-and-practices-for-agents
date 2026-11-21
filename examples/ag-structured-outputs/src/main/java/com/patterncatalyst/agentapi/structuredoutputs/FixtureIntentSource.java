package com.patterncatalyst.agentapi.structuredoutputs;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The MODEL=off decision path, forced by the {@code %test} profile ({@code
 * agent.extraction.model-source=fixture}), so {@code mvn -B verify} and CI never need Ollama. It
 * stands in for what each live path's own shape guarantee actually looks like, replayed, not
 * derived live: {@link #typedRaw} is clean JSON every time, the same shape Ollama's grammar never
 * lets the real typed path violate; {@link #untypedRaw} is the same information wrapped the way a
 * small instruct model commonly wraps an answer it was never grammar-constrained to format,
 * markdown fences and a one-line preamble, which a strict parse rejects. Both are fixed strings,
 * not a live call, exactly so {@code mvn -B verify} proves the downstream parse wiring
 * deterministically; the live shape-versus-drift contrast itself is {@code verify.sh}'s job.
 */
@ApplicationScoped
public class FixtureIntentSource {

    public String typedRaw(String message) {
        return "{\"orderId\":\"O-4821\",\"intent\":\"REFUND_REQUEST\",\"amount\":38.50}";
    }

    public String untypedRaw(String message) {
        return "Sure, here is what I found in that message:\n"
                + "```json\n"
                + "{\"orderId\":\"O-4821\",\"intent\":\"REFUND_REQUEST\",\"amount\":38.50}\n"
                + "```";
    }
}
