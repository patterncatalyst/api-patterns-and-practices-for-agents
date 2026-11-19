package com.patterncatalyst.agentapi.semanticmetadatallmstxt;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SemanticMetadataLlmsTxtTest {

    private static final Pattern LLMS_OP = Pattern.compile("#/operations/([A-Za-z0-9]+)\\)");
    private static final Pattern PORTAL_OP = Pattern.compile("data-op=\"([A-Za-z0-9]+)\"");

    private static Set<String> findAll(Pattern pattern, String text) {
        Set<String> found = new TreeSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    @Test
    void placeOrderAndGetOrderWork() {
        Response placed = given().contentType("application/json").body("""
                {"customerId":"CUST-1","itemSku":"SKU-1","quantity":2,"amount":49.99}
                """)
                .post("/orders");
        placed.then().statusCode(201);
        String orderId = placed.jsonPath().getString("orderId");

        given().get("/orders/" + orderId).then().statusCode(200).body("status", org.hamcrest.Matchers.is("PLACED"));
    }

    @Test
    void llmsTxtIsServedAsTextPlainAndIsWellFormed() {
        Response r = given().get("/llms.txt");
        r.then().statusCode(200).contentType(org.hamcrest.Matchers.startsWith("text/plain"));
        String body = r.asString();
        assertTrue(body.startsWith("# Order Service"), "llms.txt must open with an H1 title");
        assertTrue(body.contains("\n> "), "llms.txt must carry a blockquote summary");
        assertTrue(body.contains("## API reference"), "llms.txt must have an API reference section");
        assertTrue(body.contains("## Operations"), "llms.txt must have an Operations section");
        assertTrue(body.contains("[placeOrder]("), "llms.txt must link the placeOrder operation");
    }

    @Test
    void portalIsServedAsTextHtml() {
        given().get("/portal").then().statusCode(200).contentType(org.hamcrest.Matchers.startsWith("text/html"));
    }

    @Test
    void portalAndLlmsTxtNameTheSameSetOfOperations() {
        String llms = given().get("/llms.txt").asString();
        String portal = given().get("/portal").asString();

        Set<String> llmsOps = findAll(LLMS_OP, llms);
        Set<String> portalOps = findAll(PORTAL_OP, portal);

        assertEquals(Set.of("placeOrder", "getOrder"), llmsOps, "sanity: llms.txt names both operations");
        assertEquals(llmsOps, portalOps, "llms.txt and the portal must name exactly the same operations");
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
