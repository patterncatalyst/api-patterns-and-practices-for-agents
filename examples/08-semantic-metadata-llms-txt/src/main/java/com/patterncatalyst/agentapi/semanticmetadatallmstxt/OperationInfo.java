package com.patterncatalyst.agentapi.semanticmetadatallmstxt;

/** One operation's metadata, as both {@code /llms.txt} and the human portal render it. */
public record OperationInfo(String operationId, String method, String path, String summary) {
}
