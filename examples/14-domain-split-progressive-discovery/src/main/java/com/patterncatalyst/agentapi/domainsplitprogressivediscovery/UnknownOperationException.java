package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

/** No catalog entry matches a requested domain or operation id. */
public class UnknownOperationException extends RuntimeException {

    public UnknownOperationException(String message) {
        super(message);
    }
}
