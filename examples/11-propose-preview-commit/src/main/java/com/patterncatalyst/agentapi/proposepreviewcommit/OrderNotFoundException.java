package com.patterncatalyst.agentapi.proposepreviewcommit;

/** No order with the given id exists. Maps to 404, for a preview or a commit alike. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String message) {
        super(message);
    }
}
