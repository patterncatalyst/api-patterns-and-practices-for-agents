package com.patterncatalyst.agentapi.observabilityforagenttraffic;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The trigger: a plain Quarkus REST resource, no Camel route anywhere in this example. One call
 * asks {@link AgentTrafficBean} about an order and returns its answer; the GenAI span, the
 * token-usage metric and the durable {@code token_cost} row all come from what that one call
 * does downstream, not from anything this resource builds.
 */
@Path("/agent/orders")
public class AgentTrafficResource {

    @Inject
    AgentTrafficBean bean;

    @GET
    @Path("/{id}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Answer status(@PathParam("id") String id) {
        return new Answer(id, bean.ask(id));
    }

    public record Answer(String orderId, String answer) {
    }
}
