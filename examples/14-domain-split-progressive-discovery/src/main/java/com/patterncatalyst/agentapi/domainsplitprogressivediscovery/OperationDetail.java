package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

/** The per-operation detail {@code skill://{domain}/operations/{operationId}} serves. */
public record OperationDetail(String id, String domain, String kind, String liveToolName, String description,
        String howToInvoke) {

    static OperationDetail from(CatalogEntry entry) {
        String how = "tool".equals(entry.kind())
                ? "Call tools/call with name \"" + entry.liveToolName() + "\" on the " + entry.domain() + " server."
                : "Catalog-only in this release: not a live tool on either server.";
        return new OperationDetail(entry.id(), entry.domain(), entry.kind(), entry.liveToolName(),
                entry.description(), how);
    }
}
