package com.patterncatalyst.agentapi.searchoverpagination;

import com.patterncatalyst.agentapi.OrderStatus;
import java.math.BigDecimal;
import org.jboss.resteasy.reactive.RestQuery;

/**
 * Typed search filters for {@code GET /orders/search}, bound with {@code @BeanParam}. Every
 * field is optional; the resource applies only the ones a caller sets.
 */
public class OrderSearchFilter {

    @RestQuery
    public OrderStatus status;

    @RestQuery
    public String itemSku;

    @RestQuery
    public String customerId;

    @RestQuery
    public BigDecimal minAmount;

    @RestQuery
    public BigDecimal maxAmount;
}
