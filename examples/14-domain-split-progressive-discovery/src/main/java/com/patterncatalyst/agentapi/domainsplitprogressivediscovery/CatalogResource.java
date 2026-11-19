package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.ResourceTemplate;
import io.quarkiverse.mcp.server.ResourceTemplateArg;
import io.quarkiverse.mcp.server.TextResourceContents;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * The resource face of progressive discovery, bound to both servers like
 * {@link CatalogSearchTools}: a single static catalog ({@code skill://catalog}), a per-domain
 * listing ({@code skill://{domain}/operations}), and a per-operation detail
 * ({@code skill://{domain}/operations/{operationId}}, what {@link CatalogSearchTools#searchTools}
 * links to). None of the 16 catalog-only operations these serve is a live tool; an agent reads
 * about them here instead of calling them.
 */
@ApplicationScoped
@McpServer("order")
@McpServer("shipping")
public class CatalogResource {

    private static final Set<String> DOMAINS = Set.of("order", "shipping");

    @Inject
    OperationCatalog catalog;

    @Inject
    ObjectMapper objectMapper;

    @Resource(uri = "skill://catalog", name = "operation-catalog",
            description = "The full 20-operation order-to-delivery catalog across both domains: "
                    + "which operations are live tools and which are catalog-only.",
            mimeType = "application/json")
    public TextResourceContents fullCatalog() {
        return new TextResourceContents("skill://catalog", toJson(catalog.all()), "application/json");
    }

    @ResourceTemplate(uriTemplate = "skill://{domain}/operations", name = "domain-operation-list",
            description = "The operation catalog for one domain (order or shipping): which "
                    + "operations are live tools and which are catalog-only.",
            mimeType = "application/json")
    public TextResourceContents domainOperations(@ResourceTemplateArg(name = "domain") String domain) {
        requireKnownDomain(domain);
        String uri = "skill://" + domain + "/operations";
        return new TextResourceContents(uri, toJson(catalog.byDomain(domain)), "application/json");
    }

    @ResourceTemplate(uriTemplate = "skill://{domain}/operations/{operationId}", name = "operation-detail",
            description = "One operation's detail: its description and, for a live tool, which "
                    + "tool name to call; for a catalog-only operation, that it is not a live tool.",
            mimeType = "application/json")
    public TextResourceContents operationDetail(@ResourceTemplateArg(name = "domain") String domain,
            @ResourceTemplateArg(name = "operationId") String operationId) {
        requireKnownDomain(domain);
        CatalogEntry entry = catalog.find(operationId)
                .filter(e -> e.domain().equals(domain))
                .orElseThrow(() -> new UnknownOperationException(
                        "no " + domain + " operation named " + operationId));
        String uri = "skill://" + domain + "/operations/" + operationId;
        return new TextResourceContents(uri, toJson(OperationDetail.from(entry)), "application/json");
    }

    private void requireKnownDomain(String domain) {
        if (!DOMAINS.contains(domain)) {
            throw new UnknownOperationException("unknown domain " + domain + ", must be order or shipping");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize the catalog", e);
        }
    }
}
