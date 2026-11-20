package com.patterncatalyst.agentapi.identityandauthorizationguardrails;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import io.quarkus.security.ForbiddenException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * The gated tool face: {@link #orderSummary} is a plain role-gated read, the same WHO check every
 * bearer-gated MCP surface needs. {@link #cancelOrder} adds two more checks on top of that role: a
 * scope requirement (WHAT the caller is authorized to do, the same by-hand {@code scope} claim
 * check as the plain securing-and-gating pattern, for the same reason: {@code @PermissionsAllowed}
 * splits its value on the first colon, which collides with a colon-separated scope name like
 * {@code mcp:tools:write}) and an {@code acr} (Authentication Context Class Reference) floor, the
 * assurance the caller's credential itself carries. A token that clears the role and even the
 * write scope but was never stepped up still cannot reach the mutation: proving WHO and WHAT is
 * not the same as proving the credential is strong enough for a destructive action.
 */
@ApplicationScoped
public class OrderMcpTools {

    /** The client scope {@code cancelOrder} requires, from the {@code apa} realm's
     * {@code mcp:tools:write} client scope. Checked by hand against the verified token's "scope"
     * claim via {@link JsonWebToken}, not {@code @PermissionsAllowed}, for the same reason the
     * securing-and-gating pattern uses: {@code @PermissionsAllowed}'s annotation value splits on
     * the FIRST ':' into a name/action pair, which collides with a colon-separated scope name. */
    static final String WRITE_SCOPE = "mcp:tools:write";

    /** The acr value an exchanged, stepped-up token carries, stamped by the {@code apa} realm's
     * {@code step-up-acr} client scope. A token that never went through the RFC 8693 exchange has
     * no {@code acr} claim at all; a missing claim and a claim with any other value are both below
     * the floor. */
    static final String REQUIRED_STEP_UP_ACR = "urn:patterncatalyst:acr:step-up";

    @Inject
    OrderRepository orders;

    @Inject
    JsonWebToken accessToken;

    @Inject
    ObjectMapper objectMapper;

    /** The negative-witness toggle: with step-up enforcement off, {@link #requireStepUpAcr()}
     * returns immediately, so a low-acr token reaches the mutation the way it would if the floor
     * were never wired in. The write-scope check is a separate axis and is never affected by this
     * flag, so disabling it isolates exactly what the acr floor contributes. */
    @Inject
    @ConfigProperty(name = "guardrails.step-up.enforced", defaultValue = "true")
    boolean stepUpEnforced;

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

    @Tool(description = "Cancel an order and refund its amount. Only PLACED or PAID orders can be "
            + "cancelled. Requires the mcp:tools:write scope and a token whose acr claim carries "
            + "the step-up assurance level: a plain read-scoped or non-exchanged token is rejected "
            + "before the order is ever looked up.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = true,
                    idempotentHint = false, openWorldHint = false))
    @RolesAllowed("agent")
    @WrapBusinessError({ OrderNotFoundException.class, OrderNotCancellableException.class })
    public String cancelOrder(@ToolArg(description = "The order id, for example O-9001.") String orderId,
            @ToolArg(description = "Why the order is being cancelled, recorded for audit.") String reason) {
        requireWriteScope();
        requireStepUpAcr();
        OrderRepository.OrderRow order = orders.find(orderId);
        if (!order.isCancellable()) {
            throw new OrderNotCancellableException(orderId, order.status());
        }
        orders.cancelAndRefund(orderId);
        return toJson(CancelOrderResult.cancelled(orderId, order.amount()));
    }

    /** Thrown as {@link ForbiddenException}, not wrapped by {@code @WrapBusinessError}:
     * {@code quarkus-mcp-server-core}'s {@code Failures.isSecurityFailure} recognizes
     * {@code io.quarkus.security.ForbiddenException} on its own, the same path an unmet
     * {@code @RolesAllowed} already uses. */
    private void requireWriteScope() {
        String scope = accessToken.getClaim("scope");
        List<String> grantedScopes = scope == null ? List.of() : Arrays.asList(scope.split("\\s+"));
        if (!grantedScopes.contains(WRITE_SCOPE)) {
            throw new ForbiddenException(
                    "the current token does not carry the " + WRITE_SCOPE + " scope required to cancel an order");
        }
    }

    /** The step-up floor: a destructive write needs more than the right scope, it needs a
     * credential the identity provider marked as having cleared a stronger authentication context.
     * Disabled only by the {@code guardrails.step-up.enforced} flag, for the negative witness. */
    private void requireStepUpAcr() {
        if (!stepUpEnforced) {
            return;
        }
        String acr = accessToken.getClaim("acr");
        if (!REQUIRED_STEP_UP_ACR.equals(acr)) {
            throw new ForbiddenException("the current token's acr claim does not meet the step-up "
                    + "assurance level required to cancel an order");
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
