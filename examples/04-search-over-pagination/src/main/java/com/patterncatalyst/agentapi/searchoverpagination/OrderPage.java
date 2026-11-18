package com.patterncatalyst.agentapi.searchoverpagination;

import java.util.List;

/**
 * One page of the raw-pagination comparison surface, {@code GET /orders}. {@code hasMore}
 * lets a paging caller stop once it has seen every page with data, rather than needing one
 * extra call to confirm an empty page.
 */
public record OrderPage(List<OrderSummary> items, int page, int size, boolean hasMore) {
}
