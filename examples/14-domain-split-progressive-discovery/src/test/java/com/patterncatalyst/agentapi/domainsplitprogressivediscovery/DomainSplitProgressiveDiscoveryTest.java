package com.patterncatalyst.agentapi.domainsplitprogressivediscovery;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Drives both named MCP servers (order, shipping) over Streamable HTTP at their own root paths:
 * each advertises only its own small, namespaced tool set; the shared {@code searchTools} tool
 * and the catalog resources are identical on both; a search-then-fetch round trip resolves a
 * catalog-only match (no live tool) and a live-tool match (fetched, then actually called).
 */
@QuarkusTest
class DomainSplitProgressiveDiscoveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void orderServerInitializesAndAdvertisesOnlyItsOwnSmallToolSet() {
        String sessionId = mcpInitialize("/mcp/order");
        List<?> tools = mcpCall("/mcp/order", sessionId, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """).jsonPath().getList("result.tools");
        List<String> names = tools.stream().map(t -> (String) ((Map<?, ?>) t).get("name")).toList();
        assertEquals(Set.of("order_status_get", "order_cancel_eligibility_get", "searchTools"), Set.copyOf(names));
    }

    @Test
    void shippingServerInitializesAndAdvertisesOnlyItsOwnSmallToolSet() {
        String sessionId = mcpInitialize("/mcp/shipping");
        List<?> tools = mcpCall("/mcp/shipping", sessionId, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """).jsonPath().getList("result.tools");
        List<String> names = tools.stream().map(t -> (String) ((Map<?, ?>) t).get("name")).toList();
        assertEquals(Set.of("shipping_track_get", "shipping_eta_get", "searchTools"), Set.copyOf(names));
    }

    @Test
    void namespacedToolNamesDoNotCollideAcrossServers() {
        String orderSession = mcpInitialize("/mcp/order");
        List<?> orderTools = mcpCall("/mcp/order", orderSession, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """).jsonPath().getList("result.tools");
        String shippingSession = mcpInitialize("/mcp/shipping");
        List<?> shippingTools = mcpCall("/mcp/shipping", shippingSession, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """).jsonPath().getList("result.tools");
        List<String> orderNames = orderTools.stream().map(t -> (String) ((Map<?, ?>) t).get("name")).toList();
        List<String> shippingNames = shippingTools.stream().map(t -> (String) ((Map<?, ?>) t).get("name")).toList();
        for (String name : orderNames) {
            assertFalse(name.startsWith("shipping_"), name + " must not be a shipping-namespaced name on the order server");
        }
        for (String name : shippingNames) {
            assertFalse(name.startsWith("order_"), name + " must not be an order-namespaced name on the shipping server");
        }
    }

    @Test
    void orderStatusGetReturnsTheOrdersOwnStateOnly() throws Exception {
        String sessionId = mcpInitialize("/mcp/order");
        JsonNode body = MAPPER.readTree(mcpCall("/mcp/order", sessionId, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"order_status_get","arguments":{"orderId":"O-7001"}}}
                """).jsonPath().getString("result.content[0].text"));
        assertEquals("O-7001", body.path("orderId").asText());
        assertEquals("SHIPPED", body.path("status").asText());
        assertFalse(body.has("carrier"), "order_status_get must not carry any shipment field");
    }

    @Test
    void orderCancelEligibilityReflectsTheLifecycleRule() throws Exception {
        String sessionId = mcpInitialize("/mcp/order");
        JsonNode shipped = MAPPER.readTree(mcpCall("/mcp/order", sessionId, """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"order_cancel_eligibility_get","arguments":{"orderId":"O-7001"}}}
                """).jsonPath().getString("result.content[0].text"));
        assertFalse(shipped.path("cancellable").asBoolean());

        JsonNode placed = MAPPER.readTree(mcpCall("/mcp/order", sessionId, """
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"order_cancel_eligibility_get","arguments":{"orderId":"O-7002"}}}
                """).jsonPath().getString("result.content[0].text"));
        assertTrue(placed.path("cancellable").asBoolean());
    }

    @Test
    void shippingTrackGetReturnsTheShipmentsOwnStateOnly() throws Exception {
        String sessionId = mcpInitialize("/mcp/shipping");
        JsonNode body = MAPPER.readTree(mcpCall("/mcp/shipping", sessionId, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"shipping_track_get","arguments":{"orderId":"O-7001"}}}
                """).jsonPath().getString("result.content[0].text"));
        assertEquals("FASTLINE", body.path("carrier").asText());
        assertEquals("FL-440219", body.path("trackingNumber").asText());
        assertEquals("IN_TRANSIT", body.path("status").asText());
        assertFalse(body.has("customerId"), "shipping_track_get must not carry any order field");
    }

    @Test
    void shippingEtaGetDerivesAPlainLanguageWindow() throws Exception {
        String sessionId = mcpInitialize("/mcp/shipping");
        JsonNode body = MAPPER.readTree(mcpCall("/mcp/shipping", sessionId, """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"shipping_eta_get","arguments":{"orderId":"O-7001"}}}
                """).jsonPath().getString("result.content[0].text"));
        assertEquals("1 to 2 business days", body.path("etaDescription").asText());
    }

    @Test
    void catalogResourceIsIdenticalOnBothServersAndListsAllTwentyOperations() {
        for (String path : List.of("/mcp/order", "/mcp/shipping")) {
            String sessionId = mcpInitialize(path);
            Response resourcesList = mcpCall(path, sessionId, """
                    {"jsonrpc":"2.0","id":6,"method":"resources/list","params":{}}
                    """);
            assertTrue(resourcesList.jsonPath().getList("result.resources").stream()
                    .anyMatch(r -> "skill://catalog".equals(((Map<?, ?>) r).get("uri"))));

            Response read = mcpCall(path, sessionId, """
                    {"jsonrpc":"2.0","id":7,"method":"resources/read","params":{"uri":"skill://catalog"}}
                    """);
            List<?> entries;
            try {
                entries = MAPPER.readTree(read.jsonPath().getString("result.contents[0].text")).findValues("id")
                        .stream().map(JsonNode::asText).toList();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            assertEquals(20, entries.size(), "the catalog must list all 20 logical operations on " + path);
        }
    }

    @Test
    void resourceTemplatesListAdvertisesTheDomainAndDetailTemplates() {
        String sessionId = mcpInitialize("/mcp/order");
        Response templates = mcpCall("/mcp/order", sessionId, """
                {"jsonrpc":"2.0","id":8,"method":"resources/templates/list","params":{}}
                """);
        List<?> list = templates.jsonPath().getList("result.resourceTemplates");
        List<String> uriTemplates = list.stream().map(t -> (String) ((Map<?, ?>) t).get("uriTemplate")).toList();
        assertTrue(uriTemplates.contains("skill://{domain}/operations"));
        assertTrue(uriTemplates.contains("skill://{domain}/operations/{operationId}"));
    }

    @Test
    void domainOperationsTemplateListsTenPerDomain() throws Exception {
        String sessionId = mcpInitialize("/mcp/order");
        JsonNode shipping = MAPPER.readTree(mcpCall("/mcp/order", sessionId, """
                {"jsonrpc":"2.0","id":9,"method":"resources/read","params":{"uri":"skill://shipping/operations"}}
                """).jsonPath().getString("result.contents[0].text"));
        assertEquals(10, shipping.size(), "even the order server must be able to read the shipping domain's catalog listing");
    }

    @Test
    void searchThenFetchACatalogOnlyOperationReturnsItsDescriptionNotALiveCall() throws Exception {
        String sessionId = mcpInitialize("/mcp/order");
        Response search = mcpCall("/mcp/order", sessionId, """
                {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"searchTools","arguments":{"query":"refund"}}}
                """);
        List<?> links = search.jsonPath().getList("result.content");
        assertEquals(1, links.size(), "\"refund\" must match exactly one catalog operation");
        String uri = (String) ((Map<?, ?>) links.get(0)).get("uri");
        assertEquals("skill://order/operations/order_refund_post", uri);

        JsonNode detail = MAPPER.readTree(mcpCall("/mcp/order", sessionId, """
                {"jsonrpc":"2.0","id":11,"method":"resources/read","params":{"uri":"%s"}}
                """.formatted(uri)).jsonPath().getString("result.contents[0].text"));
        assertEquals("catalog", detail.path("kind").asText());
        assertTrue(detail.path("description").asText().toLowerCase(java.util.Locale.ROOT).contains("refund"));
    }

    @Test
    void searchThenFetchALiveToolOperationThenCallsItAndGetsTheRightResult() throws Exception {
        String sessionId = mcpInitialize("/mcp/shipping");
        Response search = mcpCall("/mcp/shipping", sessionId, """
                {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"searchTools","arguments":{"query":"track"}}}
                """);
        List<?> links = search.jsonPath().getList("result.content");
        assertEquals(1, links.size(), "\"track\" must match exactly one catalog operation");
        Map<?, ?> link = (Map<?, ?>) links.get(0);
        String uri = (String) link.get("uri");
        String discoveredToolName = (String) link.get("name");
        assertEquals("shipping_track_get", discoveredToolName);

        JsonNode detail = MAPPER.readTree(mcpCall("/mcp/shipping", sessionId, """
                {"jsonrpc":"2.0","id":11,"method":"resources/read","params":{"uri":"%s"}}
                """.formatted(uri)).jsonPath().getString("result.contents[0].text"));
        assertEquals("tool", detail.path("kind").asText());
        assertEquals("shipping_track_get", detail.path("liveToolName").asText());

        JsonNode called = MAPPER.readTree(mcpCall("/mcp/shipping", sessionId, """
                {"jsonrpc":"2.0","id":12,"method":"tools/call","params":{"name":"%s","arguments":{"orderId":"O-7001"}}}
                """.formatted(discoveredToolName)).jsonPath().getString("result.content[0].text"));
        assertEquals("FASTLINE", called.path("carrier").asText());
        assertEquals("FL-440219", called.path("trackingNumber").asText());
    }

    private String mcpInitialize(String path) {
        Response init = given().contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
                        "capabilities":{},"clientInfo":{"name":"domain-split-progressive-discovery-test","version":"1.0.0"}}}
                        """)
                .post(path);
        init.then().statusCode(200).body("result.protocolVersion", equalTo("2025-11-25"));
        String sessionId = init.header("Mcp-Session-Id");
        assertNotNull(sessionId, "initialize must return an Mcp-Session-Id header");
        mcpCall(path, sessionId, """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """);
        return sessionId;
    }

    private Response mcpCall(String path, String sessionId, String body) {
        return given().contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .body(body)
                .post(path);
    }
}
