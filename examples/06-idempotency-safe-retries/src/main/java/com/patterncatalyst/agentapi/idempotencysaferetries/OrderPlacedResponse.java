package com.patterncatalyst.agentapi.idempotencysaferetries;

import java.math.BigDecimal;

/** The body of a successful {@code POST /orders} response: identical, byte for byte, on a
 *  first call and on every replayed repeat of the same Idempotency-Key and body. */
public record OrderPlacedResponse(
        String orderId, String customerId, String itemSku, int quantity, BigDecimal amount, String status) {
}
