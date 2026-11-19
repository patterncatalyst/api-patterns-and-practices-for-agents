package com.patterncatalyst.agentapi.proposepreviewcommit;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.ProducerTemplate;

/**
 * The agent-facing surface: one intent-named operation, {@code POST /orders/{id}/cancel}, with a
 * {@code dryRun} flag on the same endpoint rather than a separate {@code /preview} endpoint.
 * {@code decision=REJECTED_BY_USER} is a third, graceful outcome: the caller reviewed a preview
 * and chose not to proceed, a normal terminal result, not an error.
 *
 * <p>The guard below is the one thing this chapter's negative witness removes: if a dry-run
 * request ever reaches {@link #commit}, the "preview has no side effect" contract breaks.</p>
 */
@Path("/orders")
public class OrderResource {

    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    CancelReviewService reviewService;

    @POST
    @Path("/{id}/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancel(@PathParam("id") String id, CancelRequest request) {
        if (request != null && request.isRejectedByUser()) {
            return Response.ok(new NoActionResult(id, "REJECTED_BY_USER", "no action taken")).build();
        }
        try {
            if (request != null && request.isDryRun()) {
                return Response.ok(reviewService.review(id)).build();
            }
            return commit(id, request == null ? null : request.reason());
        } catch (OrderNotFoundException notFound) {
            return Response.status(Response.Status.NOT_FOUND).entity(new Problem(notFound.getMessage())).build();
        } catch (OrderNotCancellableException conflict) {
            return Response.status(Response.Status.CONFLICT).entity(new Problem(conflict.getMessage())).build();
        }
    }

    private Response commit(String id, String reason) {
        try {
            OrderCancelledEvent event = producerTemplate.requestBodyAndHeader(
                    "direct:cancel-and-refund", reason, "orderId", id, OrderCancelledEvent.class);
            return Response.ok(event).build();
        } catch (CamelExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof OrderNotFoundException notFound) {
                throw notFound;
            }
            if (cause instanceof OrderNotCancellableException conflict) {
                throw conflict;
            }
            throw e;
        }
    }

    /** An instructive error body: what is wrong, not an opaque code. */
    public record Problem(String error) {
    }
}
