package com.patterncatalyst.agentapi.langchain4jprogrammaticconsumer;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Test-scope-only stand-in for ch13 (package-api-as-mcp-tools)'s {@code orderStatus} tool,
 * compiled into this app's own test build via {@code quarkus-mcp-server-http} (test scope) and
 * served on this same app's own {@code /mcp} endpoint. {@code %test.quarkus.langchain4j.mcp.apa-orders.url}
 * points back at that same endpoint, so {@link FixtureConsumer}'s hand-built {@code McpClient}
 * makes a real Streamable HTTP MCP round trip under {@code mvn -B verify}, never the live ch13
 * container and never Ollama. The canned response always matches the committed order id so the
 * test can assert on a known value.
 */
@ApplicationScoped
public class FixtureOrderStatusMcpServer {

    @Tool(description = "Test-only stand-in for ch13's orderStatus tool: returns a fixed composed "
            + "status for any order id so mvn verify never needs the live package-api-as-mcp-tools server.")
    public String orderStatus(@ToolArg(description = "The order id.") String orderId) {
        return "{\"orderId\":\"" + orderId + "\",\"orderStatus\":\"SHIPPED\",\"paymentStatus\":\"COMPLETED\","
                + "\"carrier\":\"FASTLINE\",\"trackingNumber\":\"FL-TEST-0001\",\"shipmentStatus\":\"IN_TRANSIT\"}";
    }
}
