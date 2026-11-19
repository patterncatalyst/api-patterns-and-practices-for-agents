package com.patterncatalyst.agentapi.publishthetoolsurface;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The single source {@link LlmsTxtResource} renders {@code /llms.txt} from: the tool surface's
 * name, summary, its three tools, and the running build's own version. The version comes from
 * {@code quarkus.application.version}, the same Maven {@code ${project.version}} compiled into
 * this jar that {@code serverInfo.version} reports over both the HTTP and stdio MCP handshakes
 * ({@code quarkus.mcp.server.server-info.version=${quarkus.application.version}} in
 * {@code application.properties}), so llms.txt and the handshake read the same value rather than
 * two independently maintained version strings that could drift apart.
 */
@ApplicationScoped
public class ToolSurfaceMetadata {

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    public String title() {
        return "Publish the Tool Surface";
    }

    public String summary() {
        return "Agent-facing order status tools, orderStatus, paymentStatus and shipmentTracking, "
                + "served over Streamable HTTP, stdio and llms.txt from one versioned build.";
    }

    public String version() {
        return version;
    }

    public List<ToolInfo> tools() {
        return List.of(
                new ToolInfo("orderStatus", "Get an order's composed status: its own state, its payment status "
                        + "and amount, and its shipment carrier, tracking number and status, all in one response. "
                        + "Use this instead of calling the order, payment and shipment reads separately."),
                new ToolInfo("paymentStatus", "Get only the payment status and amount for an order id, with "
                        + "nothing about the order's own state or its shipment."),
                new ToolInfo("shipmentTracking", "Get only the shipment carrier, tracking number and status for "
                        + "an order id, with nothing about the order's own state or its payment."));
    }

    /** One tool's name and description, as both the MCP handshake and llms.txt name it. */
    public record ToolInfo(String name, String description) {
    }
}
