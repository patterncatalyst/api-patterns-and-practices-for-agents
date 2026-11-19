package com.patterncatalyst.agentapi.replayableagentmemoryeventsourcedaudit;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;

/**
 * Keeps the live projection ({@code shipping.audit_log_projection}) current: every committed
 * record on {@link AuditTopics#AGENT_ACTIONS} is folded in, in the order the consumer reads it,
 * through {@link AuditProjectionStore#appendLive(AuditRecord)}. This fold is pure: it inserts
 * exactly what the record says, keyed on the record's own id, and never updates or deletes a row
 * it already wrote. That purity is what the chapter's checks rely on: {@link ReplayService}
 * folds the same immutable log the same way from scratch, so the two must always agree, unless
 * something bypassed this route and wrote to the projection directly (see
 * {@code AuditResource#mutate}, the demo-only, default-off exception).
 */
@ApplicationScoped
public class AuditProjectionRoute extends RouteBuilder {

    @Override
    public void configure() {
        from("kafka:" + AuditTopics.AGENT_ACTIONS + "?groupId={{audit.projection.group-id}}&autoOffsetReset=earliest")
                .routeId("audit-projection")
                .unmarshal(AuditJson.dataFormat())
                .bean(AuditProjectionStore.class, "appendLive");
    }
}
