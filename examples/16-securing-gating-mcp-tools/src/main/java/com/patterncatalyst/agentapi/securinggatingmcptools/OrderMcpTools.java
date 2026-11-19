package com.patterncatalyst.agentapi.securinggatingmcptools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.ElicitationRequest;
import io.quarkiverse.mcp.server.ElicitationResponse;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.quarkus.security.ForbiddenException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.List;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * The gated tool face: {@link #orderSummary} is a plain role-gated read; {@link #cancelOrder} adds
 * a scope requirement on top of the role, and an {@link Elicitation} confirm on top of that, so an
 * agent can see "what would happen" surfaced to the person at the keyboard before anything is
 * written. Both methods run inside an MCP request whose bearer token quarkus-oidc already
 * verified: {@link io.quarkiverse.mcp.server.runtime.SecuritySupport} (confirmed from the
 * quarkus-mcp-server-core 2.0.4 jar) propagates the resulting {@code SecurityIdentity} into every
 * tool invocation through {@code CurrentIdentityAssociation}, the same way it would into a REST
 * resource, so {@code @RolesAllowed} on a @Tool method surfaces an auth failure as an MCP error
 * with no extra wiring.
 */
@ApplicationScoped
public class OrderMcpTools {

    /** The client scope {@code cancelOrder} requires, from the {@code apa} realm's
     * {@code mcp:tools:write} client scope. Checked by hand against the verified token's "scope"
     * claim rather than {@code @PermissionsAllowed}: quarkus-oidc does map a bearer token's scope
     * claim onto {@code SecurityIdentity} permissions automatically (confirmed in the quarkus-oidc
     * Bearer token guide: "SecurityIdentity permissions are mapped in the form of
     * io.quarkus.security.StringPermission from the scope parameter"), but
     * {@code @PermissionsAllowed}'s own annotation value splits on the FIRST ':' into a
     * name/action pair (confirmed from the quarkus-security 2.3.2 jar:
     * {@code PermissionsAllowed.PERMISSION_TO_ACTION_SEPARATOR = ":"}). A scope name that is
     * itself colon-separated, like {@code mcp:tools:write}, collides with that split
     * ({@code @PermissionsAllowed("mcp:tools:write")} would check for name "mcp" and action
     * "tools:write", never matching the whole-string {@code StringPermission} OIDC actually adds).
     * {@link org.eclipse.microprofile.jwt.JsonWebToken#getClaim} against the raw "scope" claim
     * sidesteps that collision entirely. */
    static final String WRITE_SCOPE = "mcp:tools:write";

    @Inject
    OrderRepository orders;

    @Inject
    JsonWebToken accessToken;

    @Inject
    ObjectMapper objectMapper;

    @Tool(description = "Get an order's id, customer, item, quantity, amount and status.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @RolesAllowed("agent")
    @WrapBusinessError(OrderNotFoundException.class)
    public String orderSummary(@ToolArg(description = "The order id, for example O-9001.") String orderId) {
        OrderRepository.OrderRow row = orders.find(orderId);
        return toJson(new OrderSummary(row.id(), row.customerId(), row.itemSku(), row.quantity(), row.amount(),
                row.status()));
    }

    @Tool(description = "Cancel an order and refund its amount, after the person at the keyboard "
            + "explicitly confirms. Only PLACED or PAID orders can be cancelled. Requires the "
            + "mcp:tools:write scope. If the confirmation is declined, nothing is written and the "
            + "result is REJECTED_BY_USER, not an error.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = false))
    @RolesAllowed("agent")
    @WrapBusinessError({ OrderNotFoundException.class, OrderNotCancellableException.class })
    public String cancelOrder(@ToolArg(description = "The order id, for example O-9001.") String orderId,
            @ToolArg(description = "Why the order is being cancelled, shown to the person confirming it.")
            String reason, Elicitation elicitation) {
        requireWriteScope();
        OrderRepository.OrderRow order = orders.find(orderId);
        if (!order.isCancellable()) {
            throw new OrderNotCancellableException(orderId, order.status());
        }

        ElicitationRequest request = elicitation.requestBuilder()
                .setMessage("Cancel order " + orderId + " and refund " + order.amount() + "? Reason: " + reason)
                .addSchemaProperty("confirmCancellation", ElicitationRequest.BooleanSchema.builder()
                        .setTitle("Confirm cancellation")
                        .setDescription("Proceed with cancelling the order and issuing the refund?")
                        .setRequired(true)
                        .build())
                .build();
        ElicitationResponse response = request.sendAndAwait();

        if (!response.actionAccepted()) {
            return toJson(CancelOrderResult.rejectedByUser(orderId));
        }
        orders.cancelAndRefund(orderId);
        return toJson(CancelOrderResult.cancelled(orderId, order.amount()));
    }

    /** Thrown as {@link ForbiddenException}, not wrapped by {@code @WrapBusinessError}: the
     * quarkus-mcp-server-core jar's {@code Failures.isSecurityFailure} recognizes
     * {@code io.quarkus.security.ForbiddenException} (along with {@code UnauthorizedException} and
     * {@code AuthenticationException}) and surfaces it through the same security-failure path an
     * unmet {@code @RolesAllowed} already uses, so an under-scoped caller gets an MCP tool error
     * without this class needing to model it as a business exception. */
    private void requireWriteScope() {
        String scope = accessToken.getClaim("scope");
        List<String> grantedScopes = scope == null ? List.of() : Arrays.asList(scope.split("\\s+"));
        if (!grantedScopes.contains(WRITE_SCOPE)) {
            throw new ForbiddenException(
                    "the current token does not carry the " + WRITE_SCOPE + " scope required to cancel an order");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize the result", e);
        }
    }
}
