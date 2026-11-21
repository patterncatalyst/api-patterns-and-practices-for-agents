package com.patterncatalyst.agentapi.structuredoutputs;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Two endpoints over the one extraction task. {@code /extract/typed} is the path that is supposed
 * to guarantee a parseable {@link ExtractedIntent} on every call; it returns 422 the moment that
 * guarantee does not hold, whether because {@code agent.extraction.structured-output-enabled} is
 * off (the negative witness) or because {@link TypedIntentExtractor}'s guardrail exhausted its
 * retries. {@code /extract/untyped} is a diagnostic: it always answers 200 and reports whether the
 * unconstrained model's text happened to parse this time, never pretending the absence of a
 * schema is itself a failure.
 */
@Path("/extract")
public class IntentExtractionResource {

    @Inject
    IntentExtractionBean bean;

    @POST
    @Path("/typed")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public Response typed(String message) {
        ExtractionResponse response = bean.extractTyped(message);
        return Response.status(response.parseOk() ? 200 : 422).entity(response).build();
    }

    @POST
    @Path("/untyped")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public Response untyped(String message) {
        return Response.ok(bean.extractUntyped(message)).build();
    }
}
