package com.patterncatalyst.agentapi.packageapiasmcptools;

import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.TextResourceContents;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The resource face of the triad: a static, read-only playbook an agent can fetch once and reuse
 * across many {@code orderStatus} calls, instead of a model re-deriving the same status rules
 * from scratch on every turn. Loaded from the classpath, not {@code Path.of}: this is the only
 * way the content still resolves once the class ships inside the runtime jar's {@code app/} layer
 * rather than as a loose file on a development filesystem.
 */
@ApplicationScoped
public class OrderStatusPlaybookResource {

    private static final String CLASSPATH_LOCATION = "/mcp/orders-status-playbook.md";
    private static final String URI = "skill://orders/status-playbook";

    @Resource(uri = URI, name = "orders-status-playbook",
            description = "How to read a composed order status: what each order, payment and "
                    + "shipment status value means, and when the three disagree enough to need a "
                    + "human instead of a summary.",
            mimeType = "text/markdown")
    public TextResourceContents statusPlaybook() {
        try (InputStream in = getClass().getResourceAsStream(CLASSPATH_LOCATION)) {
            if (in == null) {
                throw new IllegalStateException(
                        "playbook resource missing from the classpath: " + CLASSPATH_LOCATION);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new TextResourceContents(URI, text, "text/markdown");
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load the order status playbook", e);
        }
    }
}
