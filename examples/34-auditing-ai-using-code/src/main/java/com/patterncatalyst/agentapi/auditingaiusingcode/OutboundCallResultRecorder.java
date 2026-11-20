package com.patterncatalyst.agentapi.auditingaiusingcode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * The business receipt for one outbound call's result, written regardless of whether
 * {@link AuditedToolCaller} appended an audit record for that call: auditing the call and
 * recording its business effect are two separate concerns, which is exactly what the
 * {@code audit.interceptor.enabled=false} negative witness needs to show, a call that still
 * succeeds and still produces this row even when no audit record was appended for it. Reads the
 * order status and shipment tracking number out of {@link AuditedToolCaller}'s own JSON result,
 * server side, so the durable row carries values the remote tool actually returned, including the
 * tracking number, which the model has no way to supply except by the call actually reaching it,
 * the same reasoning 24-langchain4j-programmatic-consumer's {@code OrderStatusRecorder} states.
 */
@ApplicationScoped
public class OutboundCallResultRecorder {

    private static final Logger LOG = Logger.getLogger(OutboundCallResultRecorder.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    @Inject
    public OutboundCallResultRecorder(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public String recordConsultation(String orderId, String orderStatusResult) {
        String orderStatus = "UNKNOWN";
        String trackingNumber = null;
        try {
            JsonNode node = objectMapper.readTree(orderStatusResult);
            // The remote tool returns one JSON object. On the model-driven path langchain4j hands
            // that result back as a JSON string literal (the object, escaped inside quotes), so one
            // parse yields a textual node whose text is the object; parse that once more. On the
            // fixture path the object arrives directly. Both end at the same object node.
            if (node.isTextual()) {
                node = objectMapper.readTree(node.asText());
            }
            if (node.hasNonNull("orderStatus")) {
                orderStatus = node.get("orderStatus").asText();
            }
            if (node.hasNonNull("trackingNumber")) {
                trackingNumber = node.get("trackingNumber").asText();
            }
        } catch (JsonProcessingException e) {
            LOG.warnf("orderStatus result for %s was not JSON, recording status UNKNOWN: %s", orderId,
                    orderStatusResult);
        }
        LOG.infof("business result recorded: orderId=%s status=%s tracking=%s", orderId, orderStatus, trackingNumber);
        try (Connection c = dataSource.getConnection();
                PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO consumer.outbound_call_result "
                                + "(order_id, order_status, tracking_number) VALUES (?, ?, ?) "
                                + "ON CONFLICT (order_id) DO UPDATE SET order_status = EXCLUDED.order_status, "
                                + "tracking_number = EXCLUDED.tracking_number, consulted_at = now()")) {
            insert.setString(1, orderId);
            insert.setString(2, orderStatus);
            if (trackingNumber == null) {
                insert.setNull(3, Types.VARCHAR);
            } else {
                insert.setString(3, trackingNumber);
            }
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("outbound call result record failed for " + orderId, e);
        }
        return "outbound call result recorded for " + orderId;
    }
}
