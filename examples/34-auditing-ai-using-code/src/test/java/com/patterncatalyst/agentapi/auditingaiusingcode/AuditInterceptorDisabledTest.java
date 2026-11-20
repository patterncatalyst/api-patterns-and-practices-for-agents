package com.patterncatalyst.agentapi.auditingaiusingcode;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The biting negative witness, in-JVM: with {@code audit.interceptor.enabled=false}
 * ({@link DisabledProfile}), {@link AuditedToolCaller#orderStatus(String)} still reaches the
 * remote tool and the outbound call still succeeds, but it appends no audit record for that call.
 * The live, container-level version of this same witness (recreating the compose {@code app}
 * service with {@code AUDIT_INTERCEPTOR_ENABLED=false}, then restoring it) is {@code verify.sh}'s
 * job; this test proves the same guard holds in-process, MODEL=off.
 */
@QuarkusTest
@TestProfile(AuditInterceptorDisabledTest.DisabledProfile.class)
class AuditInterceptorDisabledTest {

    public static class DisabledProfile implements io.quarkus.test.junit.QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("audit.interceptor.enabled", "false");
        }
    }

    @Inject
    DataSource dataSource;

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> auditLogFor(String correlationId) {
        return given().when().get("/audit/log/" + correlationId)
                .then().statusCode(200)
                .extract().body().jsonPath().getList("$");
    }

    private String resultColumn(String column, String orderId, Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        while (System.nanoTime() < end) {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement select = c.prepareStatement(
                            "SELECT " + column + " FROM consumer.outbound_call_result WHERE order_id = ?")) {
                select.setString(1, orderId);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        return rs.getString(1);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("could not read outbound_call_result for " + orderId, e);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    @Test
    void aDisabledInterceptorStillCallsTheRemoteToolButAppendsNoAuditRecord() {
        given().contentType("text/plain").body("O-TEST-NO-AUDIT")
                .when().post("/consume")
                .then().statusCode(200);

        // The call still succeeded: the business row is written, carrying the fixture's result.
        assertEquals("SHIPPED", resultColumn("order_status", "O-TEST-NO-AUDIT", Duration.ofSeconds(10)));
        // But the audit step was skipped: no record was ever appended for it.
        assertEquals(0, auditLogFor("O-TEST-NO-AUDIT").size(),
                "a disabled interceptor must append no audit record for an outbound call it still makes");
    }
}
