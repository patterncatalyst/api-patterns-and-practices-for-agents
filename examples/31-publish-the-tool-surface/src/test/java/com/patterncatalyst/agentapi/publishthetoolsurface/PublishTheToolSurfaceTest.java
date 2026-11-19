package com.patterncatalyst.agentapi.publishthetoolsurface;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/**
 * Checks that the Maven version compiled into this build reaches three surfaces identically: the
 * MCP {@code initialize} handshake's {@code serverInfo.version} over Streamable HTTP, the same
 * handshake field over stdio (the stdio transport is covered live by {@code verify.sh}, since a
 * Dev Services test has no detached container to pipe stdin into; this test only confirms the
 * HTTP side and that the version it reports is the Maven project version, not a hardcoded
 * literal), and the served {@code /llms.txt}. It also checks the committed tool surface:
 * {@code orderStatus}, {@code paymentStatus} and {@code shipmentTracking}.
 */
@QuarkusTest
class PublishTheToolSurfaceTest {

    private static final Set<String> EXPECTED_TOOLS = Set.of("orderStatus", "paymentStatus", "shipmentTracking");

    @Test
    void mcpInitializeReportsTheMavenProjectVersionAsServerInfoVersion() {
        String expectedVersion = ConfigProvider.getConfig().getValue("quarkus.application.version", String.class);

        Response init = given().contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
                        "capabilities":{},"clientInfo":{"name":"publish-the-tool-surface-test","version":"1.0.0"}}}
                        """)
                .post("/mcp");
        init.then().statusCode(200)
                .body("result.serverInfo.version", equalTo(expectedVersion));
    }

    @Test
    void toolsListAdvertisesExactlyTheCommittedToolSurface() {
        String sessionId = mcpInitialize();
        Response toolsList = given().contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .body("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                        """)
                .post("/mcp");
        toolsList.then().statusCode(200);
        Set<String> names = toolsList.jsonPath().getList("result.tools", java.util.Map.class).stream()
                .map(t -> (String) t.get("name"))
                .collect(Collectors.toSet());
        assertEquals(EXPECTED_TOOLS, names);
    }

    @Test
    void orderStatusToolCallComposesAllThreeReadsForTheSeededOrder() {
        String sessionId = mcpInitialize();
        Response call = given().contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Mcp-Session-Id", sessionId)
                .body("""
                        {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"orderStatus","arguments":{"orderId":"O-9001"}}}
                        """)
                .post("/mcp");
        call.then().statusCode(200);
        String text = call.jsonPath().getString("result.content[0].text");
        assertTrue(text.contains("\"orderStatus\":\"SHIPPED\""), "composed response must carry the order's own state");
        assertTrue(text.contains("\"carrier\":\"FASTLINE\""), "composed response must carry the shipment carrier");
    }

    @Test
    void llmsTxtIsServedAsTextPlainAndCarriesTheSameVersionAndTools() {
        String expectedVersion = ConfigProvider.getConfig().getValue("quarkus.application.version", String.class);

        Response r = given().get("/llms.txt");
        r.then().statusCode(200).contentType(startsWith("text/plain"));
        String body = r.asString();
        assertTrue(body.startsWith("# Publish the Tool Surface"), "llms.txt must open with an H1 title");
        assertTrue(body.contains("Version: " + expectedVersion), "llms.txt must carry the Maven project version");
        for (String tool : EXPECTED_TOOLS) {
            assertTrue(body.contains("- " + tool + ":"), "llms.txt must list the " + tool + " tool");
        }
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }

    /** Runs {@code initialize} then the required {@code notifications/initialized} notification. */
    private String mcpInitialize() {
        Response init = given().contentType("application/json")
                .header("Accept", "application/json, text/event-stream")
                .body("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",\
                        "capabilities":{},"clientInfo":{"name":"publish-the-tool-surface-test","version":"1.0.0"}}}
                        """)
                .post("/mcp");
        init.then().statusCode(200);
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
