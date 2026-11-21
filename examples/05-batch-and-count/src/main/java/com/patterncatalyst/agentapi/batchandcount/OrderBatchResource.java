package com.patterncatalyst.agentapi.batchandcount;

import com.patterncatalyst.agentapi.OrderStatus;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;

/**
 * Three surfaces over the same {@code orders.orders} table, plus the single-get baseline a
 * chatty caller is otherwise stuck with: {@code POST /orders/batch-get} (many ids, one call,
 * hits and misses separated), {@code GET /orders/count} (a bare number, never the rows behind
 * it) and {@code GET /orders/{id}} (one id, one row, the comparison baseline for the batch
 * call). {@code GET /orders} lists every row and is the comparison baseline for the count flag:
 * fetching everything and counting client-side, instead of asking the server for the count.
 */
@Path("/orders")
public class OrderBatchResource {

    private static final String SELECT_COLUMNS =
            "SELECT id, customer_id, item_sku, quantity, amount, status FROM orders.orders";

    @Inject
    DataSource dataSource;

    @POST
    @Path("/batch-get")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public BatchGetResponse batchGet(List<String> ids) {
        List<String> requested = ids == null ? List.of() : ids;
        Map<String, OrderSummary> hits = new LinkedHashMap<>();
        if (!requested.isEmpty()) {
            String sql = SELECT_COLUMNS + " WHERE id = ANY(?)";
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                Array idArray = c.createArrayOf("varchar", requested.toArray());
                ps.setArray(1, idArray);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        OrderSummary order = readRow(rs);
                        hits.put(order.id(), order);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("batch-get query failed", e);
            }
        }
        List<String> notFound = new ArrayList<>();
        for (String id : requested) {
            if (!hits.containsKey(id)) {
                notFound.add(id);
            }
        }
        return new BatchGetResponse(hits, notFound);
    }

    /** A bare number, never the rows it counted: the point of the count flag. */
    @GET
    @Path("/count")
    @Produces(MediaType.APPLICATION_JSON)
    public Long count(@RestQuery OrderStatus status) {
        String sql = status == null
                ? "SELECT COUNT(*) FROM orders.orders"
                : "SELECT COUNT(*) FROM orders.orders WHERE status = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (status != null) {
                ps.setString(1, status.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("count query failed", e);
        }
    }

    /** The single-get baseline: one id, one call, the shape a batch-get replaces for many ids. */
    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getOne(@RestPath String id) {
        OrderSummary order = queryOne(id);
        if (order == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new Problem("order " + id + " does not exist"))
                    .build();
        }
        return Response.ok(order).build();
    }

    /** The fetch-all baseline: every row, the shape a caller is stuck with when the only way to
     *  learn a count is to page through every record and count them itself. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<OrderSummary> all() {
        List<OrderSummary> results = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SELECT_COLUMNS + " ORDER BY id");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(readRow(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("fetch-all query failed", e);
        }
        return results;
    }

    private OrderSummary queryOne(String id) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SELECT_COLUMNS + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("single-get query failed for " + id, e);
        }
    }

    private OrderSummary readRow(ResultSet rs) throws SQLException {
        return new OrderSummary(
                rs.getString("id"),
                rs.getString("customer_id"),
                rs.getString("item_sku"),
                rs.getInt("quantity"),
                rs.getBigDecimal("amount"),
                OrderStatus.valueOf(rs.getString("status")));
    }
}
