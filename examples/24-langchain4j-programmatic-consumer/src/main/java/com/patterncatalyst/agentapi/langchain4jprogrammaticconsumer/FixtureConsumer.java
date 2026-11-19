package com.patterncatalyst.agentapi.langchain4jprogrammaticconsumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The MODEL=off decision path: calls the remote {@code orderStatus} MCP tool directly, through a
 * hand-built {@link McpClient} (the same {@code dev.langchain4j.mcp.client} API
 * {@code @McpToolBox} uses internally), bypassing {@link OrderStatusConsumerAgent} and Ollama
 * entirely, so {@code mvn -B verify} and CI never need a model. The client points at the same
 * {@code quarkus.langchain4j.mcp.apa-orders.url} the AiService's tool box is configured with: the
 * live compose stack's package-api-as-mcp-tools server in production, and
 * {@link FixtureOrderStatusMcpServer} (an in-process, test-scope-only MCP server exposing an
 * equivalent {@code orderStatus} tool on this same app's own {@code /mcp} endpoint) under the
 * {@code %test} profile. Either way the result reaches the same
 * {@link OrderStatusRecorder#recordConsultation(String, String)}, so the durable record, the
 * counter and the log line this example's checks assert on are identical whichever path produced
 * them, mirroring 17-event-triggered-agent-wakeup's {@code FixtureAdvisor}/{@code ShipmentPrepTool}
 * record/replay discipline.
 */
@ApplicationScoped
public class FixtureConsumer {

    private static final Logger LOG = Logger.getLogger(FixtureConsumer.class);

    @Inject
    OrderStatusRecorder recorder;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "quarkus.langchain4j.mcp.apa-orders.url")
    String mcpUrl;

    public void consume(String orderId) {
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url(mcpUrl)
                .logRequests(true)
                .logResponses(true)
                .build();
        try (McpClient client = new DefaultMcpClient.Builder()
                .clientName("ch24-fixture-consumer")
                .transport(transport)
                .build()) {
            client.listTools(); // negotiates the tool list first, as a well-behaved MCP client does
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("fixture-" + orderId)
                    .name("orderStatus")
                    .arguments("{\"orderId\":\"" + orderId + "\"}")
                    .build();
            ToolExecutionResult result = client.executeTool(request);
            String orderStatus = extractOrderStatus(result.resultText());
            LOG.infof("fixture consumer: orderId=%s orderStatus=%s (direct MCP client call)", orderId, orderStatus);
            recorder.recordConsultation(orderId, orderStatus);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "fixture consumer failed to call the remote orderStatus tool for " + orderId, e);
        }
    }

    private String extractOrderStatus(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.has("orderStatus") ? node.get("orderStatus").asText() : "UNKNOWN";
        } catch (Exception e) {
            throw new IllegalStateException("could not parse orderStatus tool result: " + json, e);
        }
    }
}
