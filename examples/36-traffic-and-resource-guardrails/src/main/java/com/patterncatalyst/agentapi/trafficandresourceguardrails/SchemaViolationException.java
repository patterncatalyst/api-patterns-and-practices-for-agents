package com.patterncatalyst.agentapi.trafficandresourceguardrails;

import java.util.List;

/** The ingest payload failed {@link IngestSchemaValidator}. Carries every violation message. */
public class SchemaViolationException extends RuntimeException {

    private final List<String> violations;

    public SchemaViolationException(List<String> violations) {
        super("ingest payload failed schema validation: " + violations);
        this.violations = violations;
    }

    public List<String> violations() {
        return violations;
    }
}
