package com.patterncatalyst.agentapi.tokenefficientresponsebff;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TokenEfficientResponseBffTest {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Test
    void fullPayloadCarriesTheHeavyAndInternalFields() {
        Response full = given().get("/orders/O-1001");
        full.then().statusCode(200);
        assertEquals("O-1001", full.jsonPath().getString("orderId"));
        assertTrue(UUID_PATTERN.matcher(full.jsonPath().getString("recordUuid")).matches());
        assertTrue(UUID_PATTERN.matcher(full.jsonPath().getString("customer.customerUuid")).matches());
        assertEquals("Jordan Rivera", full.jsonPath().getString("customer.fullName"));
        assertFalse(full.jsonPath().getString("internalNotes").isBlank());
        assertEquals(2, full.jsonPath().getList("auditTrail").size());
    }

    @Test
    void bffProjectionOmitsTheHeavyAndInternalFieldsAndCarriesNoRawUuid() {
        Response summary = given().get("/orders/O-1001/summary");
        summary.then().statusCode(200);
        Map<String, Object> body = summary.jsonPath().getMap("$");
        assertEquals(Set.of("orderId", "customerId", "itemSku", "quantity", "amount", "status"), body.keySet());
        assertEquals("O-1001", body.get("orderId"));
        assertEquals("CUST-1001", body.get("customerId"));
        assertFalse(UUID_PATTERN.matcher(summary.asString()).find(),
                "the BFF projection must carry no raw UUID anywhere in its body");
    }

    @Test
    void bffProjectionIsMaterallySmallerThanTheFullPayload() {
        int fullBytes = given().get("/orders/O-1001").asString().length();
        int summaryBytes = given().get("/orders/O-1001/summary").asString().length();
        assertTrue(summaryBytes < fullBytes,
                "summary (" + summaryBytes + " bytes) must be smaller than full (" + fullBytes + " bytes)");
    }

    @Test
    void graphQlQuerySelectingTwoFieldsReturnsExactlyThoseTwo() {
        Response response = given().contentType("application/json")
                .body(Map.of("query", "{ orderSummary(orderId: \"O-1001\") { orderId amount } }"))
                .post("/graphql");
        response.then().statusCode(200);
        Map<String, Object> order = response.jsonPath().getMap("data.orderSummary");
        assertEquals(Set.of("orderId", "amount"), order.keySet());
        assertEquals("O-1001", order.get("orderId"));
    }

    @Test
    void graphQlOrderSummariesReturnsOnlyLeanFieldsForEveryRequestedId() {
        Response response = given().contentType("application/json")
                .body(Map.of("query",
                        "{ orderSummaries(orderIds: [\"O-1001\", \"O-1002\"]) { orderId status } }"))
                .post("/graphql");
        response.then().statusCode(200);
        var ids = response.jsonPath().getList("data.orderSummaries.orderId", String.class);
        org.hamcrest.MatcherAssert.assertThat(ids, containsInAnyOrder("O-1001", "O-1002"));
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
