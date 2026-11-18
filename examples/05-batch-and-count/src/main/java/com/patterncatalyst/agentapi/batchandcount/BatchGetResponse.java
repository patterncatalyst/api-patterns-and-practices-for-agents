package com.patterncatalyst.agentapi.batchandcount;

import java.util.List;
import java.util.Map;

/**
 * The response of {@code POST /orders/batch-get}: every requested id lands in exactly one of
 * the two buckets, in the one response of the one call. A caller never has to issue a second
 * call to learn which of its ids did not exist.
 */
public record BatchGetResponse(Map<String, OrderSummary> hits, List<String> notFound) {
}
