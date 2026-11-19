package com.patterncatalyst.agentapi.semanticmetadatallmstxt;

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
import org.eclipse.microprofile.openapi.annotations.Operation;

/** The agent-facing surface: {@code placeOrder} and {@code getOrder}. Both operations are
 *  described once, in {@link ApiMetadata}, the single source that {@link LlmsTxtResource} and
 *  {@link PortalResource} both render from. */
@Path("/orders")
public class OrderResource {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    OrderService orderService;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "placeOrder", summary = "Place a new order.")
    public Response placeOrder(OrderCreateRequest request) {
        try {
            OrderResponse response = producerTemplate.requestBody("direct:place-order", request, OrderResponse.class);
            return Response.status(Response.Status.CREATED).entity(response).build();
        } catch (CamelExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IllegalArgumentException invalid) {
                return Response.status(Response.Status.BAD_REQUEST).type(PROBLEM_JSON)
                        .entity(new Problem("the order is invalid", 400, invalid.getMessage()))
                        .build();
            }
            throw e;
        }
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "getOrder", summary = "Look up an order by id.")
    public Response getOrder(@PathParam("id") String id) {
        try {
            return Response.ok(orderService.getOrder(id)).build();
        } catch (OrderNotFoundException notFound) {
            return Response.status(Response.Status.NOT_FOUND).type(PROBLEM_JSON)
                    .entity(new Problem("order not found", 404, notFound.getMessage()))
                    .build();
        }
    }
}
