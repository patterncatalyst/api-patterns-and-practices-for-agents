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

/**
 * The agent-facing ingest endpoint both guardrails sit in front of. {@code @RateLimit} is a CDI
 * interceptor: it runs before {@link #ingestOrder} ever executes, so a call over the limit never
 * reaches the schema gate at all, valid payload or not. A call that clears the rate limit is then
 * checked against {@link IngestSchemaValidator}; only a payload that clears both guardrails is
 * accepted.
 */
@Path("/ingest")
public class IngestResource {

    @Inject
    ObjectMapper mapper;

    @Inject
    IngestSchemaValidator schemaValidator;

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
        List<String> violations = schemaValidator.validate(payload);
        if (!violations.isEmpty()) {
            throw new SchemaViolationException(violations);
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
