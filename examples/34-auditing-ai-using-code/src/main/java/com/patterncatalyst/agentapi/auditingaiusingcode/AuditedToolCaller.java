package com.patterncatalyst.agentapi.auditingaiusingcode;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.apache.camel.ProducerTemplate;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The single chokepoint every outbound call this code's own agent makes must pass through,
 * whichever decision path called it: the real model, choosing to call this registered local tool
 * (see {@link OutboundCallAgent}), or {@link ConsumerBean}'s MODEL=off fixture path, calling it
 * directly with no model involved. Either way, this method FIRST appends an audit record to the
 * append-only log, THEN performs the call, never the other way around: a call that happened before
 * its audit record exists would leave a window where the call is unaccounted for if the process
 * died in between.
 *
 * <p>Deliberately not {@code quarkus-langchain4j-mcp}'s {@code @McpToolBox}: that annotation lets
 * the framework discover and execute the remote tool itself, invisibly to application code, which
 * would leave no single place to append an audit record before the call goes out. This tool is
 * registered as a LOCAL tool instead ({@code @RegisterAiService(tools = AuditedToolCaller.class)}),
 * and inside it a hand-built {@link McpClient} (the same {@code dev.langchain4j.mcp.client} API
 * {@code @McpToolBox} uses internally, the same construction
 * 24-langchain4j-programmatic-consumer's {@code FixtureConsumer} already uses for its MODEL=off
 * path) makes the real outbound call, so the audit-then-call ordering is guaranteed for every
 * caller of this method, model-driven or not.
 *
 * <p>{@code audit.interceptor.enabled} is the biting negative witness: left at its default
 * ({@code true}), every call here is audited first. Set to {@code false}, the outbound call still
 * succeeds (the target still answers, the business result downstream still gets written), but no
 * audit record is appended for it, which is the whole point of the chapter made visible: auditing
 * an outbound call is a deliberate step, not a side effect the call produces on its own.
 */
@ApplicationScoped
public class AuditedToolCaller {

    private static final Logger LOG = Logger.getLogger(AuditedToolCaller.class);
    private static final String TARGET = "orderStatus";

    @Inject
    ProducerTemplate producerTemplate;

    @ConfigProperty(name = "outbound.mcp.target-url")
    String targetUrl;

    @ConfigProperty(name = "audit.interceptor.enabled", defaultValue = "true")
    boolean interceptorEnabled;

    // Named lookupOrderStatus, deliberately distinct from the remote tool name it calls
    // (orderStatus, held in TARGET below): quarkus-mcp-server-core 2.0.4 (present for the
    // test-scope FixtureOrderStatusMcpServer) auto-bridges ANY dev.langchain4j.agent.tool.Tool
    // method into an MCP TOOL feature on the default server, keyed by method name, so a Java
    // method literally named orderStatus here would collide with the fixture's own orderStatus
    // tool at build time (confirmed live: "Duplicate feature method found for orderStatus").
    // The two names serve different purposes in any case: this is the model-facing LOCAL tool
    // name; TARGET is the REMOTE tool name AuditedToolCaller calls once it is invoked.
    @Tool("Get an order's composed status by calling the remote orderStatus tool. Every call made "
            + "through this tool is audited, on an append-only log, before it goes out.")
    public String lookupOrderStatus(String orderId) {
        if (interceptorEnabled) {
            appendAuditRecord(orderId);
        } else {
            LOG.warnf("audit.interceptor.enabled=false: calling %s for %s with NO audit record appended", TARGET,
                    orderId);
        }
        return callRemote(orderId);
    }

    private void appendAuditRecord(String orderId) {
        OutboundCallAuditRecord record = new OutboundCallAuditRecord(UUID.randomUUID().toString(), orderId,
                OutboundCallAuditRecord.OUTBOUND, TARGET, ArgumentDigest.sha256Hex(orderId), Instant.now(), null,
                null, null);
        producerTemplate.sendBody("direct:audit-append-outbound", record);
    }

    private String callRemote(String orderId) {
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url(targetUrl)
                .logRequests(true)
                .logResponses(true)
                .build();
        try (McpClient client = new DefaultMcpClient.Builder()
                .clientName("ch34-audited-tool-caller")
                .transport(transport)
                .build()) {
            client.listTools(); // negotiates the tool list first, as a well-behaved MCP client does
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("audited-" + orderId)
                    .name(TARGET)
                    .arguments("{\"orderId\":\"" + orderId + "\"}")
                    .build();
            ToolExecutionResult result = client.executeTool(request);
            return result.resultText();
        } catch (Exception e) {
            throw new IllegalStateException("audited outbound call to " + TARGET + " failed for " + orderId, e);
        }
    }
}
