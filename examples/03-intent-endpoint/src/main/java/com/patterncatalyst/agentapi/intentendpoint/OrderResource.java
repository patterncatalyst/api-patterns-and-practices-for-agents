package com.patterncatalyst.agentapi.intentendpoint;

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
 * The agent-facing surface: one intent-named operation, not the three or four CRUD calls a
 * chatty caller would otherwise need to cancel an order and refund its payment.
 */
@Path("/orders")
public class OrderResource {

    @Inject
    ProducerTemplate producerTemplate;

    @POST
    @Path("/{id}/cancel-and-refund")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelAndRefund(@PathParam("id") String id, CancelRequest request) {
        String reason = request == null ? null : request.reason();
        try {
            OrderCancelledEvent event = producerTemplate.requestBodyAndHeader(
                    "direct:cancel-and-refund", reason, "orderId", id, OrderCancelledEvent.class);
            return Response.ok(event).build();
        } catch (CamelExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof OrderNotFoundException notFound) {
                return Response.status(Response.Status.NOT_FOUND).entity(new Problem(notFound.getMessage())).build();
            }
            if (cause instanceof OrderNotCancellableException conflict) {
                return Response.status(Response.Status.CONFLICT).entity(new Problem(conflict.getMessage())).build();
            }
            throw e;
        }
    }

    /** An instructive error body: what is wrong, not an opaque code. */
    public record Problem(String error) {
    }
}
