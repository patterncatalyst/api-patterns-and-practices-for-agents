package com.patterncatalyst.agentapi.publishthetoolsurface;

import io.quarkiverse.mcp.server.ToolManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The single source {@link LlmsTxtResource} renders {@code /llms.txt} from: the tool surface's
 * name, summary, its tools, and the running build's own version. The tools come from the live MCP
 * {@link ToolManager}, so each tool's name and description are the ones {@link OrderMcpTools}
 * declares once on its {@code @Tool} methods, not a second hand-maintained copy. The version comes
 * from {@code quarkus.application.version}, the same Maven {@code ${project.version}} compiled into
 * this jar that {@code serverInfo.version} reports over both the HTTP and stdio MCP handshakes
 * ({@code quarkus.mcp.server.server-info.version=${quarkus.application.version}} in
 * {@code application.properties}), so llms.txt and the handshake read the same value rather than
 * two independently maintained version strings that could drift apart.
 */
@ApplicationScoped
public class ToolSurfaceMetadata {

    @ConfigProperty(name = "quarkus.application.version")
    String version;

    @Inject
    ToolManager toolManager;

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
        List<ToolInfo> result = new ArrayList<>();
        for (ToolManager.ToolInfo tool : toolManager) {
            result.add(new ToolInfo(tool.name(), tool.description()));
        }
        result.sort(Comparator.comparing(ToolInfo::name));
        return result;
    }

    /** One tool's name and description, as both the MCP handshake and llms.txt name it. */
    public record ToolInfo(String name, String description) {
    }
}
