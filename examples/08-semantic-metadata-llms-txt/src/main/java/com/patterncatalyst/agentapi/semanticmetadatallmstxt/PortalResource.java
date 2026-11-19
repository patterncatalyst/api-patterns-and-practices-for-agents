package com.patterncatalyst.agentapi.semanticmetadatallmstxt;

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Serves the minimal human HTML portal at {@code /portal}, rendered by Qute from the same
 * {@link ApiMetadata} bean {@link LlmsTxtResource} renders {@code /llms.txt} from.
 */
@Path("/portal")
public class PortalResource {

    @Inject
    Template portal;

    @Inject
    ApiMetadata metadata;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance portal() {
        return portal.data("title", metadata.title())
                .data("summary", metadata.summary())
                .data("operations", metadata.operations());
    }
}
