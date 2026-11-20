package com.patterncatalyst.agentapi.auditingaiusingcode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.camel.component.jackson.JacksonDataFormat;

/**
 * One {@link ObjectMapper}, shared by the Camel routes that marshal and unmarshal
 * {@link OutboundCallAuditRecord} and by {@link ReplayService}'s own plain Kafka consumer, so
 * every reader of the log parses {@code occurredAt} (a {@code java.time.Instant}) the same way.
 * Camel's own {@code marshal().json()} / {@code unmarshal().json()} DSL builds a fresh, bare
 * {@code ObjectMapper} with no modules registered, which cannot serialize
 * {@code java.time.Instant} by default; this one, explicit module is the fix.
 */
final class AuditJson {

    static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    static JacksonDataFormat dataFormat() {
        return new JacksonDataFormat(MAPPER, OutboundCallAuditRecord.class);
    }

    private AuditJson() {
    }
}
