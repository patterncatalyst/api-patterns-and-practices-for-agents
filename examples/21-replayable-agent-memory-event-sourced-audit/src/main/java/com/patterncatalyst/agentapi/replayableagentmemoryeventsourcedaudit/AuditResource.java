package com.patterncatalyst.agentapi.replayableagentmemoryeventsourcedaudit;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.camel.ProducerTemplate;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The agent-facing surface for this chapter: {@code POST /audit/shipment-advice} is one call a
 * caller makes to the agent's one tool, which appends both halves of the audit pair (the inbound
 * tool call it just consumed, and the outbound model call its decision came from); {@code GET
 * /audit/log/{orderId}} reads the live projection back; {@code POST /audit/replay} and {@code
 * POST /audit/rebuild-live} drive {@link ReplayService}; {@code POST /audit/demo/mutate} is the
 * demo-only, default-off bypass {@code verify.sh} uses to prove the log is append-only.
 */
@Path("/audit")
public class AuditResource {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    AuditModelDecisionAdvisor advisor;

    @Inject
    AuditProjectionStore store;

    @Inject
    ReplayService replay;

    @ConfigProperty(name = "audit.mutable", defaultValue = "false")
    boolean mutable;

    @POST
    @Path("/shipment-advice")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response shipmentAdvice(ShipmentAdviceRequest request) {
        if (request == null || isBlank(request.orderId()) || isBlank(request.identity())) {
            return Response.status(Response.Status.BAD_REQUEST).type(PROBLEM_JSON)
                    .entity(new Problem("orderId and identity are required", 400,
                            "POST /audit/shipment-advice needs a non-blank orderId and identity"))
                    .build();
        }

        Instant inboundAt = Instant.now();
        AuditRecord inbound = new AuditRecord(UUID.randomUUID().toString(), AuditDirection.INBOUND,
                request.orderId(), request.identity(), "shipmentAdvice.tool-call",
                "tool call consumed: shipment advice requested", null, null, null, inboundAt);
        producerTemplate.sendBody("direct:audit-append", inbound);

        AuditModelDecisionAdvisor.Decision decision = advisor.decide(request.orderId());
        AuditRecord outbound = new AuditRecord(UUID.randomUUID().toString(), AuditDirection.OUTBOUND,
                request.orderId(), "model:" + decision.modelId(), "shipmentAdvice.decision",
                "carrier=" + decision.carrier(), decision.tokensIn(), decision.tokensOut(), decision.costUsd(),
                inboundAt.plusMillis(1));
        producerTemplate.sendBody("direct:audit-append", outbound);

        return Response.accepted(new ShipmentAdviceResult(inbound.id(), outbound.id(), decision.carrier().name()))
                .build();
    }

    @GET
    @Path("/log/{orderId}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<AuditRecord> log(@PathParam("orderId") String orderId) {
        return store.rowsFor(orderId);
    }

    @POST
    @Path("/replay")
    @Produces(MediaType.APPLICATION_JSON)
    public ReplayService.Result replay() {
        return replay.run();
    }

    @POST
    @Path("/rebuild-live")
    @Produces(MediaType.APPLICATION_JSON)
    public ReplayService.RebuildResult rebuildLive() {
        return replay.rebuildLive();
    }

    @POST
    @Path("/demo/mutate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response mutate(MutateRequest request) {
        if (!mutable) {
            return Response.status(Response.Status.FORBIDDEN).type(PROBLEM_JSON)
                    .entity(new Problem("mutation disabled", 403,
                            "the audit log is append-only: append a correcting record through "
                                    + "/audit/shipment-advice instead of mutating a committed one "
                                    + "(set audit.mutable=true only to run this chapter's demo)"))
                    .build();
        }
        boolean updated = store.mutateDetail(request.id(), request.newDetail());
        if (!updated) {
            return Response.status(Response.Status.NOT_FOUND).type(PROBLEM_JSON)
                    .entity(new Problem("no such record", 404, "no live record with id " + request.id())).build();
        }
        return Response.ok(new MutateRequest(request.id(), request.newDetail())).build();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
