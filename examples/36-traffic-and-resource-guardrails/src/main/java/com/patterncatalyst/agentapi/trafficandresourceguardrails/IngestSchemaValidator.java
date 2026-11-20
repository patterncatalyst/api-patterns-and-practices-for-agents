package com.patterncatalyst.agentapi.trafficandresourceguardrails;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Guardrail 2: validates the ingest payload against the committed, draft-07
 * {@code contracts/36-ingest.schema.json} (bind-mounted read-only into the container at
 * {@code ingest.schema.path}) before {@link IngestResource#ingestOrder} ever constructs an
 * {@link IngestOrder}. Uses {@code com.networknt:json-schema-validator} 2.0.8, the newest release
 * whose public API still carries {@code com.fasterxml.jackson.databind.JsonNode}: 3.0.0 and later
 * switched to Jackson 3's {@code tools.jackson.databind.JsonNode}, incompatible with the Jackson
 * 2.21.8 {@code quarkus-bom:3.40.1.2} manages (confirmed by decompiling both jars; see
 * {@code _plans/versions.md}).
 *
 * <p>{@code schema.gate.enabled=false} (demo code, {@code SCHEMA_GATE_ENABLED} in compose) is the
 * second negative witness: {@link #validate} returns no violations unconditionally, so a payload
 * that fails the schema is accepted instead of rejected.
 */
@ApplicationScoped
public class IngestSchemaValidator {

    private final ObjectMapper mapper;
    private final String schemaPath;
    private final boolean enabled;
    private Schema schema;

    @Inject
    public IngestSchemaValidator(
            ObjectMapper mapper,
            @ConfigProperty(name = "ingest.schema.path") String schemaPath,
            @ConfigProperty(name = "schema.gate.enabled", defaultValue = "true") boolean enabled) {
        this.mapper = mapper;
        this.schemaPath = schemaPath;
        this.enabled = enabled;
    }

    @PostConstruct
    void loadSchema() {
        if (!enabled) {
            return;
        }
        try {
            JsonNode schemaNode = mapper.readTree(Files.newInputStream(Path.of(schemaPath)));
            SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);
            this.schema = registry.getSchema(schemaNode);
        } catch (IOException e) {
            throw new IllegalStateException("could not load ingest schema from " + schemaPath, e);
        }
    }

    /**
     * Every violation, as {@code "<instance path>: <message>"}. Always empty when the gate is
     * disabled, so the caller skips straight to accepting the payload.
     */
    public List<String> validate(JsonNode payload) {
        if (!enabled) {
            return List.of();
        }
        return schema.validate(payload).stream()
                .map(violation -> violation.getInstanceLocation() + ": " + violation.getMessage())
                .toList();
    }
}
