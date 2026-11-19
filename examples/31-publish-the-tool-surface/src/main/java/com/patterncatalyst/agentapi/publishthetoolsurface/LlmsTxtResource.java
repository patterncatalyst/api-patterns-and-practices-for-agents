package com.patterncatalyst.agentapi.publishthetoolsurface;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Serves {@code /llms.txt}: the same tool surface {@link OrderMcpTools} advertises over MCP, and
 * the same version its {@code serverInfo.version} reports over both transports, all read from
 * {@link ToolSurfaceMetadata}.
 */
@Path("/llms.txt")
public class LlmsTxtResource {

    @Inject
    ToolSurfaceMetadata metadata;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String llmsTxt() {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(metadata.title()).append("\n\n");
        out.append("> ").append(metadata.summary()).append("\n\n");
        out.append("Version: ").append(metadata.version()).append("\n\n");
        out.append("## Tools\n\n");
        for (ToolSurfaceMetadata.ToolInfo tool : metadata.tools()) {
            out.append("- ").append(tool.name()).append(": ").append(tool.description()).append('\n');
        }
        return out.toString();
    }
}
