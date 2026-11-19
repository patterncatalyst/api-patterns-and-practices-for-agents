package com.patterncatalyst.agentapi.instructiveerror;

import java.math.BigDecimal;

/** The body of {@code POST /orders}. */
public record OrderCreateRequest(String customerId, String itemSku, int quantity, BigDecimal amount) {
}
