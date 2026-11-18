package com.patterncatalyst.agentapi.searchoverpagination;

import com.patterncatalyst.agentapi.OrderStatus;
import jakarta.inject.Inject;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jboss.resteasy.reactive.RestQuery;

/**
 * Two surfaces over the same `orders.orders` table, so the chapter can measure, not assert,
 * the contrast: {@code GET /orders/search} (typed filters, one call isolates the target) and
 * {@code GET /orders} (raw offset pagination, the surface a chatty caller is stuck with when
 * the only tool is "give me the next page").
 */
@Path("/orders")
public class OrderSearchResource {

    /** Bounded result size: a search never returns more than this many rows in one call. */
    static final int RESULT_CAP = 50;

    @Inject
    DataSource dataSource;

    @GET
    @Path("/search")
    @Produces(MediaType.APPLICATION_JSON)
    public List<OrderSummary> search(@BeanParam OrderSearchFilter filter) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, customer_id, item_sku, quantity, amount, status FROM orders.orders WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (filter.status != null) {
            sql.append(" AND status = ?");
            params.add(filter.status.name());
        }
        if (filter.itemSku != null) {
            sql.append(" AND item_sku = ?");
            params.add(filter.itemSku);
        }
        if (filter.customerId != null) {
            sql.append(" AND customer_id = ?");
            params.add(filter.customerId);
        }
        if (filter.minAmount != null) {
            sql.append(" AND amount >= ?");
            params.add(filter.minAmount);
        }
        if (filter.maxAmount != null) {
            sql.append(" AND amount <= ?");
            params.add(filter.maxAmount);
        }
        sql.append(" ORDER BY id LIMIT ").append(RESULT_CAP);
        return query(sql.toString(), params);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public OrderPage page(@RestQuery @DefaultValue("0") int page, @RestQuery @DefaultValue("10") int size) {
        // Over-fetch by one row to learn whether another page follows, without a second
        // round trip just to find that out.
        List<OrderSummary> fetched = query(
                "SELECT id, customer_id, item_sku, quantity, amount, status FROM orders.orders "
                        + "ORDER BY id LIMIT ? OFFSET ?",
                List.of(size + 1, page * size));
        boolean hasMore = fetched.size() > size;
        List<OrderSummary> items = hasMore ? fetched.subList(0, size) : fetched;
        return new OrderPage(items, page, size, hasMore);
    }

    private List<OrderSummary> query(String sql, List<Object> params) {
        List<OrderSummary> results = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new OrderSummary(
                            rs.getString("id"),
                            rs.getString("customer_id"),
                            rs.getString("item_sku"),
                            rs.getInt("quantity"),
                            rs.getBigDecimal("amount"),
                            OrderStatus.valueOf(rs.getString("status"))));
                }
            }
            return results;
        } catch (SQLException e) {
            throw new IllegalStateException("order search query failed", e);
        }
    }
}
