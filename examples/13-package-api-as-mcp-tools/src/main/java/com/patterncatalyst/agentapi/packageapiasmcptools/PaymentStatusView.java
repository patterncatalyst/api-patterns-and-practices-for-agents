package com.patterncatalyst.agentapi.packageapiasmcptools;

import com.patterncatalyst.agentapi.PaymentStatus;
import java.math.BigDecimal;

/** One order's payment, read on its own: the {@code paymentStatus} tool's result. */
public record PaymentStatusView(String orderId, String paymentId, BigDecimal amount, PaymentStatus status) {
}
