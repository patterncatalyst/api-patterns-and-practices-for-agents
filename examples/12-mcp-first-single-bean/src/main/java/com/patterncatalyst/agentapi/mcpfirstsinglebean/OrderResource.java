package com.patterncatalyst.agentapi.mcpfirstsinglebean;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Face 1: the agent-facing HTTP surface. Delegates to {@link OrderOperations}, unchanged. */
@Path("/orders")
public class OrderResource {

    @Inject
    OrderOperations ops;

    @GET
    @Path("/{id}/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public OrderSummary summary(@PathParam("id") String id) {
        return ops.orderSummary(id);
    }

    @ServerExceptionMapper
    public Response notFound(OrderNotFoundException e) {
        return Response.status(Response.Status.NOT_FOUND).entity(e.getMessage()).build();
    }
}
