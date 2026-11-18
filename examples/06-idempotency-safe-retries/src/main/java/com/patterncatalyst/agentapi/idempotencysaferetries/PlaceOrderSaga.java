package com.patterncatalyst.agentapi.idempotencysaferetries;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.patterncatalyst.agentapi.Order;
import com.patterncatalyst.agentapi.OrderStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * What runs on each side of the Idempotent Consumer EIP: a brand new Idempotency-Key places one
 * order and stores its response for later replay ({@link #placeNewOrder}); a repeat key never
 * places a second order, it replays the stored first response verbatim, or rejects the call if
 * the body no longer matches ({@link #replayOrReject}). Both database writes in
 * {@link #placeNewOrder} happen in one transaction: either the order and its stored response
 * both exist, or neither does.
 */
@ApplicationScoped
public class PlaceOrderSaga {

    private static final int CONFLICT_STATUS = 409;

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper objectMapper;

    @Transactional
    public PlaceOrderResult placeNewOrder(String idempotencyKey, OrderCreateRequest request) {
        try (Connection c = dataSource.getConnection()) {
            String orderId = "O-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            // The domain Order record's own compact constructor validates the fields (non-blank
            // text, positive quantity, positive two-decimal amount), so a bad request fails
            // here before anything is written.
            Order order = new Order(orderId, request.customerId(), request.itemSku(), request.quantity(),
                    request.amount());
            insertOrder(c, order);

            OrderPlacedResponse response = new OrderPlacedResponse(order.id(), order.customerId(), order.itemSku(),
                    order.quantity(), order.amount(), OrderStatus.PLACED.name());
            String responseBody = toJson(response);
            insertIdempotentResponse(c, order.id(), idempotencyKey, hash(request), 201, responseBody);

            OrderPlacedEvent event = new OrderPlacedEvent(order.id(), order.customerId(), order.itemSku(),
                    order.quantity(), order.amount(), Instant.now().toString());
            return new PlaceOrderResult(201, responseBody, event);
        } catch (SQLException e) {
            throw new IllegalStateException("place-order failed for key " + idempotencyKey, e);
        }
    }

    public PlaceOrderResult replayOrReject(String idempotencyKey, OrderCreateRequest request) {
        StoredResponse stored = loadFirstResponse(idempotencyKey);
        if (stored == null) {
            // Defensive only: the Idempotent Consumer EIP only reports a duplicate once
            // placeNewOrder has committed a response for this key, so this should not happen.
            Problem problem = new Problem("Idempotency-Key recorded with no stored response", 500, idempotencyKey);
            return new PlaceOrderResult(500, toJson(problem), null);
        }
        if (stored.requestHash().equals(hash(request))) {
            return new PlaceOrderResult(stored.statusCode(), stored.responseBody(), null);
        }
        Problem conflict = new Problem(
                "Idempotency-Key reused with a different request body",
                CONFLICT_STATUS,
                "Idempotency-Key '" + idempotencyKey + "' was already used to place order " + stored.orderId()
                        + " with a different request body");
        return new PlaceOrderResult(CONFLICT_STATUS, toJson(conflict), null);
    }

    private void insertOrder(Connection c, Order order) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, order.id());
            insert.setString(2, order.customerId());
            insert.setString(3, order.itemSku());
            insert.setInt(4, order.quantity());
            insert.setBigDecimal(5, order.amount());
            insert.setString(6, OrderStatus.PLACED.name());
            insert.executeUpdate();
        }
    }

    private void insertIdempotentResponse(Connection c, String orderId, String idempotencyKey, String requestHash,
            int statusCode, String responseBody) throws SQLException {
        try (PreparedStatement insert = c.prepareStatement(
                "INSERT INTO orders.idempotent_responses "
                        + "(order_id, idempotency_key, request_hash, status_code, response_body) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, orderId);
            insert.setString(2, idempotencyKey);
            insert.setString(3, requestHash);
            insert.setInt(4, statusCode);
            insert.setString(5, responseBody);
            insert.executeUpdate();
        }
    }

    /** First-result replay: the earliest stored response for this key, by {@code created_at}. */
    private StoredResponse loadFirstResponse(String idempotencyKey) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement select = c.prepareStatement(
                        "SELECT order_id, request_hash, status_code, response_body FROM orders.idempotent_responses "
                                + "WHERE idempotency_key = ? ORDER BY created_at ASC, order_id ASC LIMIT 1")) {
            select.setString(1, idempotencyKey);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new StoredResponse(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("idempotent response lookup failed for key " + idempotencyKey, e);
        }
    }

    /** A semantic hash of the request fields: byte-identical JSON is not required, only the same values. */
    private static String hash(OrderCreateRequest request) {
        String canonical = request.customerId() + '\u0000' + request.itemSku() + '\u0000' + request.quantity()
                + '\u0000' + normalizedAmount(request.amount());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String normalizedAmount(BigDecimal amount) {
        return amount == null ? "" : amount.stripTrailingZeros().toPlainString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize " + value.getClass().getSimpleName(), e);
        }
    }

    private record StoredResponse(String orderId, String requestHash, int statusCode, String responseBody) {
    }
}
