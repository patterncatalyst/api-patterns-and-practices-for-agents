package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.ResourceLink;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * The one tool that is the same on both servers: {@code searchTools} is bound to {@code order}
 * and {@code shipping} (the repeatable {@link McpServer} annotation), so progressive discovery
 * works from either connection. It never grows the live {@code tools/list} count beyond this one
 * entry per server; it returns {@link ResourceLink}s into {@link CatalogResource}'s per-operation
 * detail resource instead of inlining the operation's description as tool-call text.
 */
@ApplicationScoped
@McpServer("order")
@McpServer("shipping")
public class CatalogSearchTools {

    @Inject
    OperationCatalog catalog;

    @Tool(name = "searchTools",
            description = "Search the full order-to-delivery operation catalog (both domains, both "
                    + "live tools and catalog-only operations) by a keyword in its name or "
                    + "description. Returns a resource link per match; read it to see whether the "
                    + "operation is a live tool to call or a catalog entry to read.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public ToolResponse searchTools(@ToolArg(description = "A keyword, for example \"refund\", \"status\" or \"track\".") String query) {
        List<ResourceLink> links = catalog.search(query).stream()
                .map(e -> new ResourceLink("skill://" + e.domain() + "/operations/" + e.id(), "application/json",
                        e.id(), null, e.description(), null, null, null))
                .toList();
        return ToolResponse.success(links);
    }
}
