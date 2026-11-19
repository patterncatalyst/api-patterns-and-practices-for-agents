package com.patterncatalyst.agentapi.tokenefficientresponsebff;

import java.util.UUID;

/**
 * The raw internal customer record, as the human-facing system of record holds it: a database
 * surrogate key ({@code customerUuid}), PII (full name, email), and an internal risk score. Part
 * of {@link OrderFull}, never of {@link OrderSummary}.
 */
public record CustomerRecord(UUID customerUuid, String customerId, String fullName, String email, int riskScore) {
}
