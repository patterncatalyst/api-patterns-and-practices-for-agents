package com.patterncatalyst.agentapi.packageapiasmcptools;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Drives the MCP tool/resource/prompt triad over Streamable HTTP for the seeded order
 * {@code O-9001}: the coarse {@code orderStatus} tool must compose the order, payment and
 * shipment reads into one response; the playbook resource and the summary prompt must both be
 * advertised and servable.
 */
@QuarkusTest
class PackageApiAsMcpToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void initializeNegotiatesTheExpectedProtocolRevision() {
        Response init = given().contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
                        "capabilities":{},"clientInfo":{"name":"package-api-as-mcp-tools-test","version":"1.0.0"}}}
                        """)
                .post("/mcp");
        init.then().statusCode(200).body("result.protocolVersion", equalTo("2025-11-25"));
    }

    @Test
    void toolsListAdvertisesAllThreeToolsWithInputSchemas() {
        String sessionId = mcpInitialize();
        Response toolsList = mcpCall(sessionId, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);
        toolsList.then().statusCode(200);
        List<?> tools = toolsList.jsonPath().getList("result.tools");
        for (String name : List.of("orderStatus", "paymentStatus", "shipmentTracking")) {
            var tool = tools.stream()
                    .filter(t -> name.equals(((Map<?, ?>) t).get("name")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("tools/list must advertise " + name));
            assertTrue(((Map<?, ?>) tool).containsKey("inputSchema"), name + " must carry an inputSchema");
        }
    }

    @Test
    void orderStatusToolComposesOrderPaymentAndShipmentIntoOneResponse() throws Exception {
        String sessionId = mcpInitialize();
        Response call = mcpCall(sessionId, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"orderStatus","arguments":{"orderId":"O-9001"}}}
                """);
        call.then().statusCode(200);
        JsonNode body = MAPPER.readTree(call.jsonPath().getString("result.content[0].text"));
        assertEquals("O-9001", body.path("orderId").asText());
        assertEquals("SHIPPED", body.path("orderStatus").asText());
        assertEquals("COMPLETED", body.path("paymentStatus").asText());
        assertEquals("FASTLINE", body.path("carrier").asText());
        assertEquals("FL-778821", body.path("trackingNumber").asText());
        assertEquals("IN_TRANSIT", body.path("shipmentStatus").asText());
    }

    @Test
    void paymentStatusAndShipmentTrackingToolsStayNarrow() throws Exception {
        String sessionId = mcpInitialize();
        JsonNode payment = MAPPER.readTree(mcpCall(sessionId, """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"paymentStatus","arguments":{"orderId":"O-9001"}}}
                """).jsonPath().getString("result.content[0].text"));
        assertEquals("COMPLETED", payment.path("status").asText());
        assertTrue(payment.path("orderId").asText().equals("O-9001"));

        JsonNode shipment = MAPPER.readTree(mcpCall(sessionId, """
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"shipmentTracking","arguments":{"orderId":"O-9001"}}}
                """).jsonPath().getString("result.content[0].text"));
        assertEquals("FASTLINE", shipment.path("carrier").asText());
        assertEquals("FL-778821", shipment.path("trackingNumber").asText());
    }

    @Test
    void resourcesListAdvertisesThePlaybookAndResourcesReadReturnsIt() {
        String sessionId = mcpInitialize();
        Response resourcesList = mcpCall(sessionId, """
                {"jsonrpc":"2.0","id":6,"method":"resources/list","params":{}}
                """);
        resourcesList.then().statusCode(200);
        List<?> resources = resourcesList.jsonPath().getList("result.resources");
        assertTrue(resources.stream().anyMatch(r -> "skill://orders/status-playbook".equals(((Map<?, ?>) r).get("uri"))),
                "resources/list must advertise skill://orders/status-playbook");

        Response read = mcpCall(sessionId, """
                {"jsonrpc":"2.0","id":7,"method":"resources/read","params":{"uri":"skill://orders/status-playbook"}}
                """);
        read.then().statusCode(200);
        String text = read.jsonPath().getString("result.contents[0].text");
        assertNotNull(text);
        assertTrue(text.contains("Order status"), "the playbook must explain order status values");
    }

    @Test
    void promptsListAdvertisesThePromptAndPromptsGetRendersIt() {
        String sessionId = mcpInitialize();
        Response promptsList = mcpCall(sessionId, """
                {"jsonrpc":"2.0","id":8,"method":"prompts/list","params":{}}
                """);
        promptsList.then().statusCode(200);
        List<?> prompts = promptsList.jsonPath().getList("result.prompts");
        assertTrue(prompts.stream().anyMatch(p -> "summarizeOrderStatus".equals(((Map<?, ?>) p).get("name"))),
                "prompts/list must advertise summarizeOrderStatus");

        Response get = mcpCall(sessionId, """
                {"jsonrpc":"2.0","id":9,"method":"prompts/get","params":{"name":"summarizeOrderStatus","arguments":{"orderId":"O-9001"}}}
                """);
        get.then().statusCode(200);
        String text = get.jsonPath().getString("result.messages[0].content.text");
        assertNotNull(text);
        assertTrue(text.contains("O-9001"), "the rendered prompt must name the requested order");
    }

    /** Runs {@code initialize} then the required {@code notifications/initialized} notification. */
    private String mcpInitialize() {
        Response init = given().contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
                        "capabilities":{},"clientInfo":{"name":"package-api-as-mcp-tools-test","version":"1.0.0"}}}
                        """)
                .post("/mcp");
        init.then().statusCode(200).body("result.protocolVersion", equalTo("2025-11-25"));
        String sessionId = init.header("Mcp-Session-Id");
        mcpCall(sessionId, """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """);
        return sessionId;
    }

    private Response mcpCall(String sessionId, String body) {
        return given().contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .body(body)
                .post("/mcp");
    }
}
