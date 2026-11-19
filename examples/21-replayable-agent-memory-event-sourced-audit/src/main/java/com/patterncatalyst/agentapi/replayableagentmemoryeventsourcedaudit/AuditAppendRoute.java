package com.patterncatalyst.agentapi.replayableagentmemoryeventsourcedaudit;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;

/**
 * The one way an {@link AuditRecord} reaches the log: {@code direct:audit-append}, called once
 * per record by {@link AuditResource} (once for the inbound tool call, once for the outbound
 * model call). The Kafka key is the record's own id, so every record lands on a partition by its
 * own identity rather than being ordered against any other record; ordering across an order's
 * two directions is read back by {@code occurred_at}, not partition order.
 */
@ApplicationScoped
public class AuditAppendRoute extends RouteBuilder {

    @Override
    public void configure() {
        from("direct:audit-append")
                .routeId("audit-append")
                .setHeader(KafkaConstants.KEY, simple("${body.id}"))
                .marshal(AuditJson.dataFormat())
                .to("kafka:" + AuditTopics.AGENT_ACTIONS);
    }
}
