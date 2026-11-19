package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

/**
 * One logical operation in the order-to-delivery catalog. {@code kind} is {@code "tool"} for the
 * four operations also wired up as live {@code @Tool} methods ({@code liveToolName} names which
 * one), and {@code "catalog"} for every other operation: named and described here, but not
 * exposed as a live tool on either server. An agent reaches a catalog-only operation by reading
 * its detail resource, never by calling it.
 */
public record CatalogEntry(String id, String domain, String kind, String liveToolName, String description) {

    public CatalogEntry {
        if (!"tool".equals(kind) && !"catalog".equals(kind)) {
            throw new IllegalArgumentException("kind must be \"tool\" or \"catalog\", got " + kind);
        }
        if ("tool".equals(kind) && liveToolName == null) {
            throw new IllegalArgumentException("a \"tool\" entry needs a liveToolName: " + id);
        }
    }

    boolean matches(String query) {
        String q = query.toLowerCase(java.util.Locale.ROOT);
        return id.toLowerCase(java.util.Locale.ROOT).contains(q)
                || description.toLowerCase(java.util.Locale.ROOT).contains(q);
    }
}
