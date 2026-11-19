package com.patterncatalyst.agentapi.semanticmetadatallmstxt;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Serves {@code /llms.txt} in the llmstxt.org shape: an H1 title, a blockquote summary, then
 * {@code ##} sections with markdown links. Rendered entirely from {@link ApiMetadata}, the same
 * bean {@link PortalResource} renders the human portal from.
 */
@Path("/llms.txt")
public class LlmsTxtResource {

    @Inject
    ApiMetadata metadata;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String llmsTxt() {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(metadata.title()).append("\n\n");
        out.append("> ").append(metadata.summary()).append("\n\n");
        out.append("## API reference\n\n");
        out.append("- [OpenAPI document](/q/openapi): the full generated contract\n\n");
        out.append("## Operations\n\n");
        for (OperationInfo op : metadata.operations()) {
            out.append("- [").append(op.operationId()).append("](/q/openapi#/operations/")
                    .append(op.operationId()).append("): ").append(op.method()).append(' ').append(op.path())
                    .append(", ").append(op.summary()).append('\n');
        }
        return out.toString();
    }
}
