package com.patterncatalyst.agentapi.tokenefficientresponsebff;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Two surfaces over the same order, so the chapter can measure the contrast, not assert it:
 * {@code GET /orders/{id}} (the full human payload) and {@code GET /orders/{id}/summary} (the
 * agent BFF projection, {@link OrderSummary}).
 */
@Path("/orders")
public class OrderResource {

    @Inject
    OrderReadService reads;

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public OrderFull full(@PathParam("id") String id) {
        return reads.full(id);
    }

    @GET
    @Path("/{id}/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public OrderSummary summary(@PathParam("id") String id) {
        return reads.summary(id);
    }
}
