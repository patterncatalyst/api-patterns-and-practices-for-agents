package com.patterncatalyst.agentapi.trafficandresourceguardrails;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Guardrail 2's schema gate: one route, {@code direct:validate-ingest}, hands the raw ingest body
 * to the Camel JSON Schema Validator component ({@code json-validator}, {@code
 * camel-quarkus-json-validator}, platform-managed by the Camel Quarkus platform BOM) against the
 * committed, draft-07 {@code contracts/36-ingest.schema.json}. A payload that fails validation
 * throws {@code org.apache.camel.component.jsonvalidator.JsonValidationException}; {@link
 * IngestResource} calls this route through a {@code ProducerTemplate}, catches the resulting
 * {@code CamelExecutionException}, and maps the cause to the same {@link SchemaViolationException}
 * {@link GuardrailExceptionMappers} already turns into a 400 problem+json. {@code
 * schema.gate.enabled} ({@code SCHEMA_GATE_ENABLED} in compose) is read by {@link IngestResource}
 * itself, not this route: when the gate is disabled, the route is never called, so an invalid
 * payload reaches order construction unchecked.
 */
@ApplicationScoped
public class IngestSchemaValidationRoute extends RouteBuilder {

    @ConfigProperty(name = "ingest.schema.path")
    String schemaPath;

    @Override
    public void configure() {
        from("direct:validate-ingest")
                .routeId("validate-ingest")
                .to("json-validator:file:" + schemaPath);
    }
}
