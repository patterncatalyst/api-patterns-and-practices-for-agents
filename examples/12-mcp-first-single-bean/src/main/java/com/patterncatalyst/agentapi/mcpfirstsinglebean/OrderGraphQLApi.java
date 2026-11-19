package com.patterncatalyst.agentapi.mcpfirstsinglebean;

import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * Face 2: the read-only GraphQL surface, {@code @Query} only, same shape as ch09's
 * {@code OrderGraphQLApi}. Delegates to {@link OrderOperations}, unchanged.
 */
@GraphQLApi
public class OrderGraphQLApi {

    @Inject
    OrderOperations ops;

    @Query("orderSummary")
    @Description("The lean order summary, by human-readable order id. Identical to the REST and MCP faces.")
    public OrderSummary orderSummary(@Name("orderId") String orderId) {
        return ops.orderSummary(orderId);
    }
}
