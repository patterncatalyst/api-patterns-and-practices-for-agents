package com.patterncatalyst.agentapi.batchandcount;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OrderBatchTest {

    // Seeded by V2__seed.sql: n in 1..36, status cycles every 4 (n % 4 == 0 is PLACED, 9 rows).
    // O-0004 (n=4) is PLACED, O-0005 (n=5) is PAID; O-9999 does not exist.

    @Test
    void batchGetIsolatesHitsAndNotFoundInOneCall() {
        given().contentType("application/json").body("""
                ["O-0004", "O-0005", "O-9999"]
                """)
                .post("/orders/batch-get")
                .then().statusCode(200)
                .body("hits.'O-0004'.id", equalTo("O-0004"))
                .body("hits.'O-0004'.status", equalTo("PLACED"))
                .body("hits.'O-0005'.id", equalTo("O-0005"))
                .body("hits.'O-0005'.status", equalTo("PAID"))
                .body("notFound", contains("O-9999"));
    }

    @Test
    void countReturnsOnlyANumberNoRecordBodies() {
        String body = given().queryParam("status", "PLACED")
                .get("/orders/count")
                .then().statusCode(200)
                .extract().asString();
        assertTrue(body.trim().matches("\\d+"), "count body must be a bare number, was: " + body);
        assertEquals("9", body.trim());
    }

    @Test
    void unfilteredCountCoversEveryRow() {
        String body = given().get("/orders/count").then().statusCode(200).extract().asString();
        assertEquals("36", body.trim());
    }

    @Test
    void singleGetBaselineReturns404ForAnUnknownId() {
        given().get("/orders/O-9999").then().statusCode(404);
    }

    @Test
    void singleGetBaselineReturnsOneRecord() {
        given().get("/orders/O-0004").then().statusCode(200).body("id", equalTo("O-0004"));
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
