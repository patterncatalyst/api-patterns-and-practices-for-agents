package com.patterncatalyst.agentapi.auditingaiusingcode;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;

/**
 * The one way an {@link OutboundCallAuditRecord} reaches the log: {@code direct:audit-append-outbound},
 * called once per outbound call by {@link AuditedToolCaller}, always before that call goes out. The
 * Kafka key is the record's own id, so every record lands on a partition by its own identity rather
 * than being ordered against any other record for the same correlation id.
 */
@ApplicationScoped
public class AuditAppendOutboundRoute extends RouteBuilder {

    @Override
    public void configure() {
        from("direct:audit-append-outbound")
                .routeId("audit-append-outbound")
                .setHeader(KafkaConstants.KEY, simple("${body.id}"))
                .marshal(AuditJson.dataFormat())
                .to("kafka:" + AuditTopics.OUTBOUND_CALLS);
    }
}
