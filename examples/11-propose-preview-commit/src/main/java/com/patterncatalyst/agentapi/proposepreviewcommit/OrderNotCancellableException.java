package com.patterncatalyst.agentapi.proposepreviewcommit;

/**
 * The order exists but is not in a cancellable state (only PLACED and PAID are, per
 * `_plans/domain-model.md`'s lifecycle rule). Maps to 409, for a preview or a commit alike: a
 * preview answers honestly that there is nothing legal to propose.
 */
public class OrderNotCancellableException extends RuntimeException {

    public OrderNotCancellableException(String message) {
        super(message);
    }
}
