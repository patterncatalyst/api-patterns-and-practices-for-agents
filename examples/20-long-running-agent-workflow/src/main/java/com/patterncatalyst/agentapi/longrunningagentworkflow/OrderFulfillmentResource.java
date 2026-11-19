package com.patterncatalyst.agentapi.longrunningagentworkflow;

import com.patterncatalyst.agentapi.Order;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.ProducerTemplate;

/**
 * The agent-facing trigger: an agent (or any caller) posts an order and starts the long-running
 * fulfillment saga. The call is synchronous for this demo (small, in-process steps, no real
 * multi-hour duration), so the response already reflects the saga's outcome: 201 once every step,
 * including the agent's carrier choice, has completed, or 422 once a failed step's compensations
 * have already unwound the earlier ones. Either way {@code GET /orders/{id}} reads the same
 * {@code orders.order_fulfillment} row verify.sh polls.
 */
@Path("/orders")
public class OrderFulfillmentResource {

    @Inject
    ProducerTemplate producer;

    @Inject
    FulfillmentService service;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response fulfill(Order order) {
        try {
            producer.sendBodyAndHeader("direct:fulfillOrder", order, "orderId", order.id());
            return Response.status(Response.Status.CREATED)
                    .entity(Map.of("orderId", order.id(), "status", service.statusOf(order.id())))
                    .build();
        } catch (CamelExecutionException e) {
            return Response.status(422)
                    .entity(Map.of("orderId", order.id(), "status", service.statusOf(order.id()),
                            "reason", rootMessage(e)))
                    .build();
        }
    }

    @GET
    @Path("/{orderId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("orderId") String orderId) {
        String status = service.statusOf(orderId);
        if (status == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(Map.of("orderId", orderId, "status", status)).build();
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
