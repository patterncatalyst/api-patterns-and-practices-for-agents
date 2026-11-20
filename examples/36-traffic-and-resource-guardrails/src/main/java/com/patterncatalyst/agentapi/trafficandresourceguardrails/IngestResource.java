package com.patterncatalyst.agentapi.trafficandresourceguardrails;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.faulttolerance.api.RateLimit;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.jsonvalidator.JsonValidationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The agent-facing ingest endpoint both guardrails sit in front of. {@code @RateLimit} is a CDI
 * interceptor: it runs before {@link #ingestOrder} ever executes, so a call over the limit never
 * reaches the schema gate at all, valid payload or not. A call that clears the rate limit is then
 * routed through {@code direct:validate-ingest} ({@link IngestSchemaValidationRoute}); only a
 * payload that clears both guardrails is accepted.
 */
@Path("/ingest")
public class IngestResource {

    @Inject
    ObjectMapper mapper;

    @Inject
    ProducerTemplate producerTemplate;

    @ConfigProperty(name = "schema.gate.enabled", defaultValue = "true")
    boolean schemaGateEnabled;

    /**
     * 5 calls per 30-second window: a SmallRye Fault Tolerance addition beyond the MicroProfile
     * Fault Tolerance spec (confirmed against {@code quarkus-smallrye-fault-tolerance}'s own
     * guide: "Adding Resiliency: Rate Limits"). The rate-limit state is a singleton keyed by this
     * bean class and this method, not by caller. An over-limit call throws
     * {@code io.smallrye.faulttolerance.api.RateLimitException}, mapped by
     * {@link GuardrailExceptionMappers#mapRateLimited} to a 429 problem+json. Demo code: the live
     * negative witness overrides this globally at runtime
     * ({@code QUARKUS_FAULT_TOLERANCE_GLOBAL_RATE_LIMIT_VALUE}), since the annotation's own
     * {@code value}/{@code window} members are compile-time constants, not read from
     * {@code application.properties}.
     */
    @POST
    @Path("/orders")
    @RateLimit(value = 5, window = 30, windowUnit = ChronoUnit.SECONDS)
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response ingestOrder(String rawBody) {
        JsonNode payload = parse(rawBody);
        if (schemaGateEnabled) {
            validateAgainstSchema(rawBody);
        }
        IngestOrder order = toIngestOrder(payload);
        return Response.status(Response.Status.ACCEPTED).entity(IngestAck.accepted(order.orderId())).build();
    }

    private JsonNode parse(String rawBody) {
        try {
            return mapper.readTree(rawBody);
        } catch (IOException e) {
            throw new SchemaViolationException(List.of("body: not valid JSON"));
        }
    }

    /**
     * Routes the raw body to {@code direct:validate-ingest}. A payload that fails the registered
     * schema surfaces as a {@code JsonValidationException} wrapped in a
     * {@code CamelExecutionException} (the shape every {@code ProducerTemplate} call that fails
     * downstream throws), unwrapped here and converted to the same {@link SchemaViolationException}
     * the rate-limit-adjacent guardrail code already maps to a 400 problem+json.
     */
    private void validateAgainstSchema(String rawBody) {
        try {
            producerTemplate.requestBody("direct:validate-ingest", rawBody, String.class);
        } catch (CamelExecutionException e) {
            if (e.getCause() instanceof JsonValidationException jsonValidationException) {
                throw new SchemaViolationException(violations(jsonValidationException));
            }
            throw e;
        }
    }

    private List<String> violations(JsonValidationException e) {
        if (e.getErrors().isEmpty()) {
            return List.of("body: not valid JSON");
        }
        return e.getErrors().stream().map(error -> error.getInstanceLocation() + ": " + error.getMessage()).toList();
    }

    private IngestOrder toIngestOrder(JsonNode payload) {
        try {
            return mapper.treeToValue(payload, IngestOrder.class);
        } catch (IOException e) {
            // A payload that cleared the schema gate always converts; this only guards the gate
            // itself being disabled (SCHEMA_GATE_ENABLED=false) in front of a payload the schema
            // would otherwise have rejected.
            throw new SchemaViolationException(List.of("body: does not match the ingest shape"));
        }
    }
}
