package com.patterncatalyst.agentapi.strictselfdescribingcontract;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The agent-facing surface: a strict, self-describing contract. Every operation carries a
 * stable {@code operationId} (so a generated client or an agent's tool registry can bind to it
 * by name, not by guessing from the path and verb), and every error response is
 * {@code application/problem+json} with a {@link Problem} schema, never a bare string or an
 * opaque code.
 */
@Path("/orders")
public class OrderResource {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    OrderService orderService;

    @Inject
    ObjectMapper objectMapper;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "placeOrder", summary = "Place a new order.",
            description = "Creates an order and publishes OrderPlaced. Every field in the request body is required.")
    @RequestBody(required = true,
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = OrderCreateRequest.class)))
    @APIResponse(responseCode = "201", description = "The order was placed.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = OrderResponse.class)))
    @APIResponse(responseCode = "400", description = "The request is invalid.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
    public Response placeOrder(String body) {
        // The body is read as text and parsed here, so a malformed or wrong-typed body returns the
        // same documented 400 problem+json as any other invalid order. Letting the framework bind
        // it directly would turn a parse failure into a bare 400 the published contract never
        // describes, which is exactly the gap a conformance check exists to catch.
        OrderCreateRequest request;
        try {
            request = objectMapper.readValue(body, OrderCreateRequest.class);
        } catch (Exception parse) {
            return Response.status(Response.Status.BAD_REQUEST).type(PROBLEM_JSON)
                    .entity(new Problem("the order is invalid", 400, "the request body is not a valid order"))
                    .build();
        }
        if (request == null) {
            return Response.status(Response.Status.BAD_REQUEST).type(PROBLEM_JSON)
                    .entity(new Problem("the order is invalid", 400, "a request body is required"))
                    .build();
        }
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
    @Operation(operationId = "getOrder", summary = "Look up an order by id.",
            description = "Returns the order's current state, including its lifecycle status.")
    @APIResponse(responseCode = "200", description = "The order was found.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = OrderResponse.class)))
    @APIResponse(responseCode = "404", description = "No order exists with the given id.",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = Problem.class)))
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
