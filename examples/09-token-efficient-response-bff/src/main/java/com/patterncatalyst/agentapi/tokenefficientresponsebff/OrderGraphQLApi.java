package com.patterncatalyst.agentapi.tokenefficientresponsebff;

import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * The read-only GraphQL surface: the same lean {@link OrderSummary} projection as the REST BFF
 * endpoint, over the same {@link OrderReadService}, so a GraphQL caller gets the same byte
 * efficiency a REST caller gets, plus the ability to select even fewer fields than
 * {@link OrderSummary} carries. {@code @Query} only: no mutations. This chapter is about a read
 * surface's shape, not about writes.
 */
@GraphQLApi
public class OrderGraphQLApi {

    @Inject
    OrderReadService reads;

    @Query("orderSummary")
    @Description("The lean order summary, by human-readable order id.")
    public OrderSummary orderSummary(@Name("orderId") String orderId) {
        return reads.summary(orderId);
    }

    @Query("orderSummaries")
    @Description("The lean order summary for every seeded order.")
    public List<OrderSummary> orderSummaries(@Name("orderIds") List<String> orderIds) {
        return orderIds.stream().map(reads::summary).toList();
    }
}
