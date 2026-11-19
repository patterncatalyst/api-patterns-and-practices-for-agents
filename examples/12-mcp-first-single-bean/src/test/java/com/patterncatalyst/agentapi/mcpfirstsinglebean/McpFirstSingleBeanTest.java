package com.patterncatalyst.agentapi.mcpfirstsinglebean;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

/**
 * The same seeded order, read three ways: a plain REST GET, a GraphQL query selecting the same
 * fields, and an MCP {@code tools/call} over Streamable HTTP. All three must return identical
 * business data because all three delegate to the same {@link OrderOperations#orderSummary}.
 */
@QuarkusTest
class McpFirstSingleBeanTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void restReturnsTheSeededOrderSummary() {
        given().get("/orders/O-9001/summary")
                .then().statusCode(200)
                .body("orderId", equalTo("O-9001"))
                .body("customerId", equalTo("CUST-77"))
                .body("itemSku", equalTo("SKU-4"))
                .body("quantity", equalTo(3))
                .body("amount", equalTo(88.50f))
                .body("status", equalTo("PLACED"));
    }

    @Test
    void graphQlReturnsTheSameOrderSummary() {
        var body = """
                {"query":"{ orderSummary(orderId: \\"O-9001\\") { orderId customerId itemSku quantity amount status } }"}
                """;
        given().contentType("application/json").body(body)
                .post("/graphql")
                .then().statusCode(200)
                .body("data.orderSummary.orderId", equalTo("O-9001"))
                .body("data.orderSummary.customerId", equalTo("CUST-77"))
                .body("data.orderSummary.itemSku", equalTo("SKU-4"))
                .body("data.orderSummary.quantity", equalTo(3))
                .body("data.orderSummary.amount", equalTo(88.50f))
                .body("data.orderSummary.status", equalTo("PLACED"));
    }

    @Test
    void restGraphQlAndMcpReturnIdenticalBusinessResults() throws Exception {
        JsonNode restBody = MAPPER.readTree(given().get("/orders/O-9001/summary").thenReturn().asString());

        var graphQlQuery = """
                {"query":"{ orderSummary(orderId: \\"O-9001\\") { orderId customerId itemSku quantity amount status } }"}
                """;
        JsonNode graphQlBody = MAPPER.readTree(
                given().contentType("application/json").body(graphQlQuery).post("/graphql").thenReturn().asString())
                .path("data").path("orderSummary");

        JsonNode mcpBody = MAPPER.readTree(callMcpOrderSummaryTool("O-9001"));

        assertEquals(restBody.path("orderId").asText(), graphQlBody.path("orderId").asText());
        assertEquals(restBody.path("orderId").asText(), mcpBody.path("orderId").asText());
        assertEquals(restBody.path("customerId").asText(), graphQlBody.path("customerId").asText());
        assertEquals(restBody.path("customerId").asText(), mcpBody.path("customerId").asText());
        assertEquals(restBody.path("itemSku").asText(), graphQlBody.path("itemSku").asText());
        assertEquals(restBody.path("itemSku").asText(), mcpBody.path("itemSku").asText());
        assertEquals(restBody.path("quantity").asInt(), graphQlBody.path("quantity").asInt());
        assertEquals(restBody.path("quantity").asInt(), mcpBody.path("quantity").asInt());
        assertEquals(restBody.path("amount").decimalValue(), graphQlBody.path("amount").decimalValue());
        assertEquals(restBody.path("amount").decimalValue(), mcpBody.path("amount").decimalValue());
        assertEquals(restBody.path("status").asText(), graphQlBody.path("status").asText());
        assertEquals(restBody.path("status").asText(), mcpBody.path("status").asText());
    }

    @Test
    void mcpToolsListAdvertisesTheOrderSummaryToolWithAnInputSchema() {
        String sessionId = mcpInitialize();
        Response toolsList = given().contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .body("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                        """)
                .post("/mcp");
        toolsList.then().statusCode(200);
        var tools = toolsList.jsonPath().getList("result.tools");
        assertTrue(tools.stream().anyMatch(t -> "orderSummary".equals(((java.util.Map<?, ?>) t).get("name"))),
                "tools/list must advertise a tool named orderSummary");
        var tool = tools.stream().filter(t -> "orderSummary".equals(((java.util.Map<?, ?>) t).get("name"))).findFirst()
                .orElseThrow();
        assertTrue(((java.util.Map<?, ?>) tool).containsKey("inputSchema"), "the orderSummary tool must carry an inputSchema");
    }

    /** Runs the initialize / notifications/initialized / tools/call sequence, returns the tool's text content. */
    private String callMcpOrderSummaryTool(String orderId) {
        String sessionId = mcpInitialize();
        Response call = given().contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .body("""
                        {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"orderSummary","arguments":{"orderId":"%s"}}}
                        """.formatted(orderId))
                .post("/mcp");
        call.then().statusCode(200);
        return call.jsonPath().getString("result.content[0].text");
    }

    /** Runs {@code initialize} then the required {@code notifications/initialized} notification. */
    private String mcpInitialize() {
        Response init = given().contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
                        "capabilities":{},"clientInfo":{"name":"mcp-first-single-bean-test","version":"1.0.0"}}}
                        """)
                .post("/mcp");
        init.then().statusCode(200)
                .body("result.protocolVersion", equalTo("2025-11-25"));
        String sessionId = init.header("Mcp-Session-Id");
        given().contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .body("""
                        {"jsonrpc":"2.0","method":"notifications/initialized"}
                        """)
                .post("/mcp");
        return sessionId;
    }
}
