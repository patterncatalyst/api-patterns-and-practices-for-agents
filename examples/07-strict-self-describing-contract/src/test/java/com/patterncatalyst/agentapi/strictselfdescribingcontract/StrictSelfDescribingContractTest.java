package com.patterncatalyst.agentapi.strictselfdescribingcontract;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

@QuarkusTest
class StrictSelfDescribingContractTest {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    private List<ConsumerRecord<String, String>> drain(Duration wait) {
        var props = Map.<String, Object>of(
                "bootstrap.servers", bootstrapServers,
                "group.id", "test-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest");
        var records = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("apa.orders.placed"));
            long end = System.nanoTime() + wait.toNanos();
            while (System.nanoTime() < end) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    @Test
    void placeOrderCreatesAnOrderAndPublishesOnePlacedEvent() {
        Response placed = given().contentType("application/json").body("""
                {"customerId":"CUST-1","itemSku":"SKU-1","quantity":2,"amount":49.99}
                """)
                .post("/orders");
        placed.then().statusCode(201);
        String orderId = placed.jsonPath().getString("orderId");
        assertEquals("PLACED", placed.jsonPath().getString("status"));

        var records = drain(Duration.ofSeconds(10));
        var published = records.stream().filter(r -> orderId.equals(r.key())).toList();
        assertEquals(1, published.size(), "exactly one OrderPlaced record keyed by " + orderId);
    }

    @Test
    void getOrderReturnsTheOrderJustPlaced() {
        Response placed = given().contentType("application/json").body("""
                {"customerId":"CUST-2","itemSku":"SKU-2","quantity":1,"amount":10.00}
                """)
                .post("/orders");
        String orderId = placed.jsonPath().getString("orderId");

        given().get("/orders/" + orderId).then().statusCode(200)
                .body("orderId", org.hamcrest.Matchers.is(orderId))
                .body("status", org.hamcrest.Matchers.is("PLACED"));
    }

    @Test
    void getOrderWithAnUnknownIdIsRejectedWith404ProblemJson() {
        given().get("/orders/O-NOPE").then().statusCode(404).contentType("application/problem+json");
    }

    @Test
    void placeOrderWithAnInvalidQuantityIsRejectedWith400ProblemJson() {
        given().contentType("application/json").body("""
                {"customerId":"CUST-3","itemSku":"SKU-1","quantity":0,"amount":5.00}
                """)
                .post("/orders")
                .then().statusCode(400).contentType("application/problem+json");
    }

    @Test
    void openApiDocumentDeclaresTheStrictContract() {
        Response openapi = given().accept("application/json").get("/q/openapi");
        openapi.then().statusCode(200);

        assertTrue(openapi.jsonPath().getString("openapi").startsWith("3.1"),
                "openapi version must be 3.1.x, was " + openapi.jsonPath().getString("openapi"));

        assertEquals("placeOrder", openapi.jsonPath().getString("paths.'/orders'.post.operationId"));
        assertEquals("getOrder", openapi.jsonPath().getString("paths.'/orders/{id}'.get.operationId"));

        List<String> enumeration = openapi.jsonPath()
                .getList("components.schemas.OrderResponse.properties.status.enum", String.class);
        assertEquals(5, enumeration.size());
        org.hamcrest.MatcherAssert.assertThat(enumeration,
                containsInAnyOrder("PLACED", "PAID", "SHIPPED", "DELIVERED", "CANCELLED"));

        List<String> required = openapi.jsonPath().getList("components.schemas.OrderCreateRequest.required", String.class);
        org.hamcrest.MatcherAssert.assertThat(required,
                containsInAnyOrder("customerId", "itemSku", "quantity", "amount"));

        org.hamcrest.MatcherAssert.assertThat(
                openapi.jsonPath().getMap("paths.'/orders'.post.responses.'400'.content").keySet(),
                hasItem("application/problem+json"));
        org.hamcrest.MatcherAssert.assertThat(
                openapi.jsonPath().getMap("paths.'/orders/{id}'.get.responses.'404'.content").keySet(),
                hasItem("application/problem+json"));
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
