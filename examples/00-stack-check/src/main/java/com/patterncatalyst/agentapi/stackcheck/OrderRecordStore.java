package com.patterncatalyst.agentapi.stackcheck;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * One insert and one select against the {@code orders} schema that Flyway
 * migrated, proving the schema-per-service PostgreSQL setup works end to end.
 */
@ApplicationScoped
public class OrderRecordStore {

    private static final Logger LOG = Logger.getLogger(OrderRecordStore.class);

    @Inject
    DataSource dataSource;

    @Transactional
    public void recordAndVerify(String orderId) {
        // Schema-qualified: the connection's search_path varies between Dev Services (tests,
        // default public) and compose (?currentSchema=orders), so the table name does not rely
        // on either.
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO orders.stack_check (id, note) VALUES (?, ?) ON CONFLICT (id) DO NOTHING")) {
                insert.setString(1, orderId);
                insert.setString(2, "order intake recorded");
                insert.executeUpdate();
            }
            try (PreparedStatement select = c.prepareStatement("SELECT note FROM orders.stack_check WHERE id = ?")) {
                select.setString(1, orderId);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        LOG.infof("orders schema round trip ok for %s: %s", orderId, rs.getString(1));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("orders schema round trip failed for " + orderId, e);
        }
    }
}
