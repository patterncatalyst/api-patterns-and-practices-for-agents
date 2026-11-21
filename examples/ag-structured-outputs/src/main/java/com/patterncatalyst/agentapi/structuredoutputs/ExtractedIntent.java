package com.patterncatalyst.agentapi.structuredoutputs;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * The typed boundary this appendix is about: an order id, a closed-vocabulary {@link Intent}, and
 * a non-negative dollar amount, extracted from one free-text customer message. A compact
 * constructor rejects a value that is shape-valid JSON but semantically wrong (a blank order id, a
 * negative amount), which is the "validate and repair" lever from the lever map, a weaker,
 * complementary check behind the stronger one: Ollama's grammar-constrained decoding, driven by
 * the JSON Schema {@code quarkus-langchain4j-ollama} derives from this very record and sends as
 * the model request's {@code format}, never lets the model emit a shape this constructor would
 * have to reject in the first place.
 *
 * <p>Each component carries {@code @JsonProperty(required = true)}. Confirmed live against the
 * resolved langchain4j-core 1.19.3 jar's {@code dev.langchain4j.service.output.JsonSchemas}: the
 * schema it derives from a record marks a field required only if that field's reflected
 * {@code java.lang.reflect.Field} carries a Jackson {@code @JsonProperty(required = true)}
 * annotation (checked by {@code dev.langchain4j.internal.JsonSchemaElementUtils.isRequired});
 * every other field defaults to optional in the derived schema, {@code
 * JsonSchemas.jsonSchemaFrom} hard-codes {@code areSubFieldsRequiredByDefault = false}. Without
 * this annotation, Ollama's grammar-constrained decoding only enforces that a present field
 * matches its declared type, never that the field is present at all: a live run of this exact
 * example without the annotation had the model return {@code {"orderId":"O-4821"}} alone, a
 * grammar-valid but incomplete object that the compact constructor above then rejects for a
 * missing {@code intent}.
 */
public record ExtractedIntent(
        @JsonProperty(required = true) String orderId,
        @JsonProperty(required = true) Intent intent,
        @JsonProperty(required = true) BigDecimal amount) {

    public ExtractedIntent {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        if (intent == null) {
            throw new IllegalArgumentException("intent must not be null");
        }
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
    }
}
