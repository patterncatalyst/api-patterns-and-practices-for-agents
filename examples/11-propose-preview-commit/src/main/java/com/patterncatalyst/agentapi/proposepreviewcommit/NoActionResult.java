package com.patterncatalyst.agentapi.proposepreviewcommit;

/**
 * The graceful, terminal outcome of {@code decision=REJECTED_BY_USER}: a normal 200, not an
 * error, naming that nothing was written and nothing was published.
 */
public record NoActionResult(String orderId, String decision, String message) {
}
