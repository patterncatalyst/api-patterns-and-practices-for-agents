package com.patterncatalyst.agentapi.structuredoutputs;

/**
 * The one system message shared, word for word, by {@link TypedIntentExtractor} and
 * {@link UntypedIntentExtractor}. It states the extraction task only: it never tells the model to
 * reply in JSON, never names a field, never shows an example response. That instruction is what
 * {@link TypedIntentExtractor}'s declared return type gets for free, from the schema
 * {@code quarkus-langchain4j-ollama} derives from {@link ExtractedIntent} and sends as Ollama's
 * {@code format}, so the one thing that is allowed to differ between the two AiServices is the
 * declared return type itself, never the words asking for the answer.
 */
final class ExtractionPrompt {

    private ExtractionPrompt() {
    }

    static final String SYSTEM_MESSAGE = """
            You are an order support assistant for an online shipping company. Read the \
            customer's message and identify three things about it: the order id the customer \
            mentions, the customer's intent (one of STATUS_INQUIRY, asking where an order is; \
            REFUND_REQUEST, asking for money back; CANCEL_REQUEST, asking to cancel an order that \
            has not shipped yet; COMPLAINT, unhappy about an order with no specific ask; or OTHER, \
            anything else), and the dollar amount the customer names, or zero if the message does \
            not name one.
            """;
}
