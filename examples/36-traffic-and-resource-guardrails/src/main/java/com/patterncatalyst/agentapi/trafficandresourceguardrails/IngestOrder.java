package com.patterncatalyst.agentapi.trafficandresourceguardrails;

import java.math.BigDecimal;

/**
 * The body of {@code POST /ingest/orders}, parsed only after it has already cleared the committed
 * {@code contracts/36-ingest.schema.json}. Field names mirror the shared {@code Order} record
 * ({@code com.patterncatalyst.agentapi.Order}); this is an intake shape for the ingest gate, not a
 * new domain entity.
 */
public record IngestOrder(String orderId, String customerId, String itemSku, int quantity, BigDecimal amount) {
}
