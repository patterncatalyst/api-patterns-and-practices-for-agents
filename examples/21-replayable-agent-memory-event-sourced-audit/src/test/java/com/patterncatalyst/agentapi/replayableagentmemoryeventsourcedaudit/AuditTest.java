package com.patterncatalyst.agentapi.replayableagentmemoryeventsourcedaudit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Exercises the whole append-to-fold-to-replay path against Dev Services Kafka and PostgreSQL.
 * There is no live model anywhere in this chapter (see {@link AuditModelDecisionAdvisor}): the
 * CORE assertion is a pure log/fold, so these tests need no MODEL=off switch to force, only the
 * committed {@code fixtures/model-decisions.jsonl} {@link AuditModelDecisionAdvisor} always
 * reads.
 */
@QuarkusTest
class AuditTest {

    private static String shipmentAdvice(String orderId, String identity) {
        String body = """
                {"orderId":"%s","identity":"%s"}
                """.formatted(orderId, identity);
        return given().contentType(ContentType.JSON).body(body)
                .when().post("/audit/shipment-advice")
                .then().statusCode(202)
                .extract().body().asString();
    }

    @SuppressWarnings("unchecked")
    private static List<java.util.Map<String, Object>> logFor(String orderId) {
        return given().when().get("/audit/log/" + orderId)
                .then().statusCode(200)
                .extract().body().jsonPath().getList("$");
    }

    @Test
    void anOrderNamedInTheFixtureIsRecordedWithTheRecordedCarrierAndTokenCounts() {
        String result = shipmentAdvice("O-9101", "agent:shipping-ui");
        assertTrue(result.contains("\"carrier\":\"AIRCO\""), result);

        List<java.util.Map<String, Object>> rows = logFor("O-9101");
        assertEquals(2, rows.size());
        var outbound = rows.stream().filter(r -> "OUTBOUND".equals(r.get("direction"))).findFirst().orElseThrow();
        assertEquals(58, ((Number) outbound.get("tokensIn")).intValue());
        assertEquals(16, ((Number) outbound.get("tokensOut")).intValue());
    }

    @Test
    void anOrderNotInTheFixtureFallsBackToFastline() {
        shipmentAdvice("O-9102", "agent:shipping-ui");

        List<java.util.Map<String, Object>> rows = logFor("O-9102");
        var outbound = rows.stream().filter(r -> "OUTBOUND".equals(r.get("direction"))).findFirst().orElseThrow();
        assertTrue(outbound.get("detail").toString().contains("FASTLINE"));
        assertEquals(30, ((Number) outbound.get("tokensIn")).intValue());
        assertEquals(10, ((Number) outbound.get("tokensOut")).intValue());
    }

    @Test
    void bothAuditDirectionsAppearForOneShipmentAdviceCall() {
        shipmentAdvice("O-9103", "agent:shipping-ui");

        Set<String> directions = logFor("O-9103").stream().map(r -> r.get("direction").toString())
                .collect(Collectors.toSet());
        assertEquals(Set.of("INBOUND", "OUTBOUND"), directions);
    }

    @Test
    void replayReproducesTheLiveProjectionAfterNormalAppends() {
        shipmentAdvice("O-9104", "agent:shipping-ui");

        given().when().post("/audit/replay")
                .then().statusCode(200)
                .body("matchesLive", org.hamcrest.Matchers.is(true))
                .body("recordsReplayed", org.hamcrest.Matchers.greaterThanOrEqualTo(2));
    }

    @Test
    void mutationIsRejectedByDefault() {
        shipmentAdvice("O-9105", "agent:shipping-ui");
        String outboundId = logFor("O-9105").stream().filter(r -> "OUTBOUND".equals(r.get("direction")))
                .findFirst().orElseThrow().get("id").toString();

        String body = """
                {"id":"%s","newDetail":"tampered"}
                """.formatted(outboundId);
        given().contentType(ContentType.JSON).body(body)
                .when().post("/audit/demo/mutate")
                .then().statusCode(403);

        var row = logFor("O-9105").stream().filter(r -> "OUTBOUND".equals(r.get("direction"))).findFirst()
                .orElseThrow();
        assertTrue(row.get("detail").toString().contains("AIRCO"), "mutation must not have changed the row");
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
