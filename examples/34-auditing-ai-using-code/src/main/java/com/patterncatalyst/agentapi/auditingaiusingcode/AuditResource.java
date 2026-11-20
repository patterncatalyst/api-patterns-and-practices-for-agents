package com.patterncatalyst.agentapi.auditingaiusingcode;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The read and replay surface for the outbound-call audit log: {@code GET /audit/log/{correlationId}}
 * reads the live projection back for one correlation id (here, an order id); {@code POST /audit/replay}
 * and {@code POST /audit/rebuild-live} drive {@link ReplayService}; {@code POST /audit/demo/mutate} is
 * the demo-only, default-off bypass {@code verify.sh} uses to prove the log is append-only. There is no
 * endpoint here that triggers an outbound call: that is {@code POST /consume}
 * ({@link TriggerRoute}), which calls {@link AuditedToolCaller} directly.
 */
@Path("/audit")
public class AuditResource {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Inject
    AuditProjectionStore store;

    @Inject
    ReplayService replay;

    @ConfigProperty(name = "audit.mutable", defaultValue = "false")
    boolean mutable;

    @GET
    @Path("/log/{correlationId}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<OutboundCallAuditRecord> log(@PathParam("correlationId") String correlationId) {
        return store.rowsFor(correlationId);
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
                            "the outbound call audit log is append-only: append a correcting record "
                                    + "through a new outbound call instead of mutating a committed one "
                                    + "(set audit.mutable=true only to run this chapter's demo)"))
                    .build();
        }
        boolean updated = store.mutateArgumentDigest(request.id(), request.newArgumentDigest());
        if (!updated) {
            return Response.status(Response.Status.NOT_FOUND).type(PROBLEM_JSON)
                    .entity(new Problem("no such record", 404, "no live record with id " + request.id())).build();
        }
        return Response.ok(new MutateRequest(request.id(), request.newArgumentDigest())).build();
    }
}
