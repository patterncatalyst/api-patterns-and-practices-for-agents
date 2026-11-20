package com.patterncatalyst.agentapi.identityandauthorizationguardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.patterncatalyst.agentapi.OrderStatus;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link OrderMcpTools} directly as an injected CDI bean, the same approach the securing-
 * and-gating example's test uses: {@code @RolesAllowed} and the two by-hand checks are plain
 * method calls plus a CDI role interceptor, so they fire on a direct method call exactly as they
 * would on a call arriving over {@code /mcp}. {@code @TestSecurity} simulates the role;
 * {@code @OidcSecurity}'s {@code @Claim} simulates the token's "scope" and "acr" claims, the same
 * claims a real RFC 8693 exchange would stamp onto a stepped-up token. Neither needs a live
 * Keycloak or Dev Services for Keycloak (see application.properties).
 */
@QuarkusTest
class OrderMcpToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WRITE_SCOPE = "mcp:tools:write";
    private static final String READ_SCOPE = "mcp:tools:read";
    private static final String STEP_UP_ACR = "urn:patterncatalyst:acr:step-up";

    @Inject
    OrderMcpTools tools;

    @Inject
    OrderRepository orders;

    @Test
    @TestSecurity(user = "caller-without-the-agent-role")
    void orderSummaryRequiresTheAgentRole() {
        assertThrows(ForbiddenException.class, () -> tools.orderSummary("O-9101"));
    }

    @Test
    @TestSecurity(user = "agent-under-test", roles = "agent")
    void orderSummaryReturnsTheSeededOrderForAnAgentCaller() throws Exception {
        JsonNode body = MAPPER.readTree(tools.orderSummary("O-9101"));
        assertEquals("O-9101", body.path("orderId").asText());
        assertEquals("PLACED", body.path("status").asText());
    }

    @Test
    @TestSecurity(user = "agent-under-test", roles = "agent")
    void orderSummarySurfacesAnUnknownOrderAsAnInstructiveToolError() {
        ToolCallException failure = assertThrows(ToolCallException.class, () -> tools.orderSummary("O-0000"));
        assertTrue(messageChain(failure).contains("no order with id O-0000"));
    }

    @Test
    @TestSecurity(user = "agent-under-test", roles = "agent")
    @OidcSecurity(claims = { @Claim(key = "scope", value = READ_SCOPE), @Claim(key = "acr", value = STEP_UP_ACR) })
    void cancelOrderRejectsAMissingWriteScopeEvenWithAStepUpAcr() {
        // The token even carries the step-up acr: proving WHAT (the scope) is a separate axis from
        // proving the credential's assurance level, and both must clear.
        assertThrows(ForbiddenException.class, () -> tools.cancelOrder("O-9102", "customer requested"));

        assertEquals(OrderStatus.PLACED, orders.find("O-9102").status(),
                "a token missing the write scope must not cancel the order even with a high acr");
    }

    @Test
    @TestSecurity(user = "agent-under-test", roles = "agent")
    @OidcSecurity(claims = { @Claim(key = "scope", value = WRITE_SCOPE) })
    void cancelOrderRejectsAWriteScopedTokenBelowTheStepUpFloor() {
        // The token carries the write scope but was never exchanged for a step-up credential, so
        // it has no acr claim at all: the floor treats a missing claim the same as a low one.
        assertThrows(ForbiddenException.class, () -> tools.cancelOrder("O-9103", "customer requested"));

        assertEquals(OrderStatus.PLACED, orders.find("O-9103").status(),
                "a write-scoped token below the step-up floor must not cancel the order");
    }

    @Test
    @TestSecurity(user = "agent-under-test", roles = "agent")
    @OidcSecurity(claims = { @Claim(key = "scope", value = WRITE_SCOPE), @Claim(key = "acr", value = STEP_UP_ACR) })
    void cancelOrderRejectsAnOrderThatIsAlreadyDelivered() {
        ToolCallException failure = assertThrows(ToolCallException.class,
                () -> tools.cancelOrder("O-9104", "customer requested"));

        assertTrue(messageChain(failure).contains("cannot be cancelled"));
    }

    @Test
    @TestSecurity(user = "agent-under-test", roles = "agent")
    @OidcSecurity(claims = { @Claim(key = "scope", value = WRITE_SCOPE), @Claim(key = "acr", value = STEP_UP_ACR) })
    void cancelOrderCancelsAndRefundsWithWriteScopeAndStepUpAcr() throws Exception {
        JsonNode body = MAPPER.readTree(tools.cancelOrder("O-9105", "customer requested"));

        assertEquals("O-9105", body.path("orderId").asText());
        assertEquals("CANCELLED", body.path("outcome").asText());
        assertEquals(30.00, body.path("refundedAmount").asDouble(), 0.001);
        assertEquals(OrderStatus.CANCELLED, orders.find("O-9105").status());
    }

    /** The instructive text may sit on the thrown {@link ToolCallException} or on its cause; walk
     * the chain so the assertion holds either way. */
    private static String messageChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null) {
                sb.append(c.getMessage()).append(' ');
            }
        }
        return sb.toString();
    }
}
