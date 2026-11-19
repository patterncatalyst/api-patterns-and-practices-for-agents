package com.patterncatalyst.agentapi.instructiveerror;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class InstructiveErrorTest {

    private Response placeOrder(String customerId, String itemSku, int quantity, String amount) {
        return given().contentType("application/json").body("""
                {"customerId":"%s","itemSku":"%s","quantity":%d,"amount":%s}
                """.formatted(customerId, itemSku, quantity, amount))
                .post("/orders");
    }

    @Test
    void getUnknownOrderIsAnInstructiveNotFoundProblem() {
        Response response = given().get("/orders/O-NOPE");
        response.then().statusCode(404).contentType("application/problem+json");
        assertEquals("https://errors.patterncatalyst.dev/problems/order-not-found", response.jsonPath().getString("type"));
        assertEquals(404, response.jsonPath().getInt("status"));
        assertFalse(response.jsonPath().getString("title").isBlank());
        assertFalse(response.jsonPath().getString("detail").isBlank());
        assertFalse(response.jsonPath().getString("nextStep").isBlank());
    }

    @Test
    void placingAnOrderWithAnInvalidQuantityIsAnInstructiveValidationProblemNamingTheField() {
        Response response = placeOrder("CUST-1", "SKU-1", 0, "5.00");
        response.then().statusCode(400).contentType("application/problem+json");
        assertEquals("https://errors.patterncatalyst.dev/problems/order-validation", response.jsonPath().getString("type"));
        assertEquals(400, response.jsonPath().getInt("status"));
        assertEquals("quantity", response.jsonPath().getString("field"));
    }

    @Test
    void placingAnOrderWithABlankCustomerIdNamesThatFieldInstead() {
        Response response = placeOrder("", "SKU-1", 1, "5.00");
        response.then().statusCode(400).contentType("application/problem+json");
        assertEquals("customerId", response.jsonPath().getString("field"));
    }

    @Test
    void cancellingAPlacedOrderSucceedsThenASecondCancelIsAnInstructiveConflictProblem() {
        Response placed = placeOrder("CUST-2", "SKU-2", 1, "10.00");
        placed.then().statusCode(201);
        String orderId = placed.jsonPath().getString("orderId");

        given().post("/orders/" + orderId + "/cancel").then().statusCode(200).body("status", org.hamcrest.Matchers.is("CANCELLED"));

        Response secondCancel = given().post("/orders/" + orderId + "/cancel");
        secondCancel.then().statusCode(409).contentType("application/problem+json");
        assertEquals("https://errors.patterncatalyst.dev/problems/order-not-cancellable", secondCancel.jsonPath().getString("type"));
        assertEquals(409, secondCancel.jsonPath().getInt("status"));
        assertEquals("CANCELLED", secondCancel.jsonPath().getString("currentState"));
        assertEquals(List.of(), secondCancel.jsonPath().getList("allowedTransitions"));
    }

    @Test
    void cancellingASeededShippedOrderNamesDeliveredAsTheOnlyAllowedTransition() {
        Response response = given().post("/orders/O-2002/cancel");
        response.then().statusCode(409).contentType("application/problem+json");
        assertEquals("SHIPPED", response.jsonPath().getString("currentState"));
        org.hamcrest.MatcherAssert.assertThat(
                response.jsonPath().getList("allowedTransitions", String.class), containsInAnyOrder("DELIVERED"));
    }

    @Test
    void cancellingAnUnknownOrderIsAnInstructiveNotFoundProblem() {
        given().post("/orders/O-NOPE/cancel").then().statusCode(404).contentType("application/problem+json");
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
