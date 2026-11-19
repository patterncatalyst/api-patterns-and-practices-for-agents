package com.patterncatalyst.agentapi.humanintheloopgate;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The agent-facing surface: {@code POST /orders/{orderId}/cancel-request} is the trigger. The
 * order's existence and cancellability are checked here, before the agent is ever called, so a
 * 404 or 409 never depends on model behaviour on either the {@code ollama} or the {@code
 * fixture} path. A validated order is handed to the agent, which classifies the reason and opens
 * the gate (see {@link CancelProposalTool}); the response is the gate's own state, {@code
 * PENDING}, never a cancellation result, since nothing has been cancelled yet. {@code GET
 * /orders/{orderId}/cancel-gate} reads the gate back, before or after a decision resumes it.
 */
@Path("/orders")
public class CancelRequestResource {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Inject
    CancelProposalAdvisor advisor;

    @Inject
    FixtureCancelAdvisor fixtureAdvisor;

    @Inject
    CancelGateService gateService;

    @ConfigProperty(name = "agent.cancelgate.model-source", defaultValue = "ollama")
    String modelSource;

    @POST
    @Path("/{orderId}/cancel-request")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response requestCancel(@PathParam("orderId") String orderId, CancelRequest request) {
        String reasonText = request == null || request.reason() == null ? "" : request.reason();
        try {
            gateService.requireCancellable(orderId);
        } catch (OrderNotFoundException notFound) {
            return Response.status(Response.Status.NOT_FOUND).type(PROBLEM_JSON)
                    .entity(new Problem("order not found", 404, notFound.getMessage())).build();
        } catch (OrderNotCancellableException conflict) {
            return Response.status(Response.Status.CONFLICT).type(PROBLEM_JSON)
                    .entity(new Problem("order not cancellable", 409, conflict.getMessage())).build();
        }

        if ("fixture".equals(modelSource)) {
            fixtureAdvisor.propose(orderId, reasonText);
        } else {
            advisor.propose("Cancel order " + orderId + " and refund its payment. Reason: " + reasonText);
        }

        return Response.accepted(gateService.latestGateFor(orderId)).build();
    }

    @GET
    @Path("/{orderId}/cancel-gate")
    @Produces(MediaType.APPLICATION_JSON)
    public Response gate(@PathParam("orderId") String orderId) {
        CancelGateRecord record = gateService.latestGateFor(orderId);
        if (record == null) {
            return Response.status(Response.Status.NOT_FOUND).type(PROBLEM_JSON)
                    .entity(new Problem("no such gate", 404,
                            "no cancel-gate proposal exists yet for order " + orderId))
                    .build();
        }
        return Response.ok(record).build();
    }
}
