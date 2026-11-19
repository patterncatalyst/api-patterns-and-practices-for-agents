package com.patterncatalyst.agentapi.instructiveerror;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.ProducerTemplate;

/**
 * The agent-facing surface. Every domain exception a write can raise is left to propagate: no
 * manual Response-building here beyond unwrapping Camel's {@link CamelExecutionException}, so
 * {@link OrderExceptionMappers} is the single place each error's actual shape is decided.
 */
@Path("/orders")
public class OrderResource {

    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    OrderService orderService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response placeOrder(OrderCreateRequest request) {
        try {
            OrderResponse response = producerTemplate.requestBody("direct:place-order", request, OrderResponse.class);
            return Response.status(Response.Status.CREATED).entity(response).build();
        } catch (CamelExecutionException e) {
            throw unwrap(e);
        }
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public OrderResponse getOrder(@PathParam("id") String id) {
        return orderService.getOrder(id);
    }

    @POST
    @Path("/{id}/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    public OrderResponse cancelOrder(@PathParam("id") String id) {
        try {
            return producerTemplate.requestBody("direct:cancel-order", id, OrderResponse.class);
        } catch (CamelExecutionException e) {
            throw unwrap(e);
        }
    }

    private static RuntimeException unwrap(CamelExecutionException e) {
        Throwable cause = e.getCause();
        return cause instanceof RuntimeException re ? re : e;
    }
}
