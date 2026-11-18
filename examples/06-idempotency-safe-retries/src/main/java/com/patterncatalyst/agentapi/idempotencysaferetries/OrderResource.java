package com.patterncatalyst.agentapi.idempotencysaferetries;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.ProducerTemplate;
import org.jboss.resteasy.reactive.RestHeader;

/**
 * The agent-facing surface: {@code POST /orders} accepts an {@code Idempotency-Key} header, so
 * a caller that retries after a timeout (or an agent that is not sure its previous call
 * landed) gets the exact same result back instead of placing the order twice.
 */
@Path("/orders")
public class OrderResource {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Inject
    ProducerTemplate producerTemplate;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response placeOrder(@RestHeader(PlaceOrderRoute.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            OrderCreateRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).type(PROBLEM_JSON)
                    .entity(new Problem("Idempotency-Key header is required", 400,
                            "POST /orders needs an Idempotency-Key header to make a retry safe"))
                    .build();
        }
        try {
            PlaceOrderResult result = producerTemplate.requestBodyAndHeader(
                    "direct:place-order", request, PlaceOrderRoute.IDEMPOTENCY_KEY_HEADER, idempotencyKey,
                    PlaceOrderResult.class);
            String type = result.statusCode() >= 400 ? PROBLEM_JSON : MediaType.APPLICATION_JSON;
            return Response.status(result.statusCode()).type(type).entity(result.body()).build();
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
}
