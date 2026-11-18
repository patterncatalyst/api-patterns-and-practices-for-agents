package com.patterncatalyst.agentapi.searchoverpagination;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OrderSearchTest {

    // Seeded by V2__seed.sql: n in 1..60, status cycles every 5, item_sku cycles every 4.
    // n % 5 == 0 is PLACED; of those, n % 4 == 1 (n = 5, 25, 45) is SKU-2.
    private static final String[] EXPECTED_PLACED_SKU2 = {"O-0005", "O-0025", "O-0045"};

    @Test
    void searchReturnsExactlyTheMatchingOrdersAndNoOthers() {
        var ids = given().queryParam("status", "PLACED").queryParam("itemSku", "SKU-2")
                .get("/orders/search")
                .then().statusCode(200)
                .body("", hasSize(EXPECTED_PLACED_SKU2.length))
                .body("status", everyItem(equalTo("PLACED")))
                .body("itemSku", everyItem(equalTo("SKU-2")))
                .extract().jsonPath().getList("id", String.class);

        for (String expected : EXPECTED_PLACED_SKU2) {
            org.junit.jupiter.api.Assertions.assertTrue(ids.contains(expected), expected + " must be in the result");
        }
    }

    @Test
    void searchResultIsBoundedByTheCap() {
        given().get("/orders/search")
                .then().statusCode(200)
                .body("", hasSize(lessThanOrEqualTo(OrderSearchResource.RESULT_CAP)));
    }

    @Test
    void pageReportsHasMoreUntilTheLastPage() {
        given().queryParam("page", 0).queryParam("size", 10)
                .get("/orders")
                .then().statusCode(200)
                .body("items", hasSize(10))
                .body("hasMore", equalTo(true));

        // 60 seeded rows, page size 10: page 5 (the 6th page, 0-indexed) is the last one.
        given().queryParam("page", 5).queryParam("size", 10)
                .get("/orders")
                .then().statusCode(200)
                .body("items", hasSize(10))
                .body("hasMore", equalTo(false));
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
