package com.patterncatalyst.agentapi.semanticmetadatallmstxt;

/** No order with the given id exists. Maps to 404. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String message) {
        super(message);
    }
}
