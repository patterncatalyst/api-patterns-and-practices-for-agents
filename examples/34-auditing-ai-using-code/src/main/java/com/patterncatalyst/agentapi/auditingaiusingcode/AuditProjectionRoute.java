package com.patterncatalyst.agentapi.auditingaiusingcode;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;

/**
 * Keeps the live projection ({@code audit.outbound_call_log}) current: every committed record on
 * {@link AuditTopics#OUTBOUND_CALLS} is folded in, in the order the consumer reads it, through
 * {@link AuditProjectionStore#appendLive(OutboundCallAuditRecord)}. This fold is pure: it inserts
 * exactly what the record says, keyed on the record's own id, and never updates or deletes a row
 * it already wrote, the same discipline
 * 21-replayable-agent-memory-event-sourced-audit's {@code AuditProjectionRoute} uses for the
 * inward log. That purity is what {@link ReplayService} relies on: folding the same immutable log
 * from scratch must always reproduce this projection, unless something bypassed this route and
 * wrote to it directly (see {@code AuditResource#mutate}, the demo-only, default-off exception).
 */
@ApplicationScoped
public class AuditProjectionRoute extends RouteBuilder {

    @Override
    public void configure() {
        from("kafka:" + AuditTopics.OUTBOUND_CALLS + "?groupId={{audit.projection.group-id}}&autoOffsetReset=earliest")
                .routeId("audit-outbound-projection")
                .unmarshal(AuditJson.dataFormat())
                .bean(AuditProjectionStore.class, "appendLive");
    }
}
