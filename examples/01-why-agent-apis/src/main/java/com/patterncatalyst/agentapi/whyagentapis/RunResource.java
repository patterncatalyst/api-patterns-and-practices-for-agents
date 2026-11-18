package com.patterncatalyst.agentapi.whyagentapis;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Triggers one live run of the cancel-and-refund task against a contract. Used only in LIVE
 * mode (see README and verify.sh); the default replay path never calls this, and never needs
 * Ollama running.
 */
@Path("/run")
public class RunResource {

    @Inject
    AssistantRunner runner;

    @GET
    @Path("/good")
    @Produces(MediaType.APPLICATION_JSON)
    public RunOutcome good() {
        return runner.runGood();
    }

    @GET
    @Path("/bad")
    @Produces(MediaType.APPLICATION_JSON)
    public RunOutcome bad() {
        return runner.runBad();
    }
}
