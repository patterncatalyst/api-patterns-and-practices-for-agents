package com.patterncatalyst.agentapi.securinggatingmcptools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.patterncatalyst.agentapi.OrderStatus;
import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.ElicitationRequest;
import io.quarkiverse.mcp.server.ElicitationResponse;
import io.quarkiverse.mcp.server.Meta;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link OrderMcpTools} directly as an injected CDI bean (the same approach ch15's
 * {@code PaymentMcpToolsTest} uses): {@code @RolesAllowed}, the manual scope check and
 * {@code @WrapBusinessError} are all CDI interceptors, so they fire on a direct method call
 * exactly as they would on a call arriving over {@code /mcp}. {@code @TestSecurity} simulates the
 * role; {@code @OidcSecurity}'s {@code @Claim} simulates the token's "scope" claim; neither needs a
 * live Keycloak or Dev Services for Keycloak (see application.properties). The {@link Elicitation}
 * confirm is driven with a Mockito stub built by {@link #respondingWith}, since it is a plain
 * method parameter here, not something the test needs the MCP runtime to inject.
 */
@QuarkusTest
class OrderMcpToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    OrderMcpTools tools;

    @Inject
    OrderRepository orders;

    @Test
    @TestSecurity(user = "caller-without-the-agent-role")
    void orderSummaryRequiresTheAgentRole() {
        assertThrows(ForbiddenException.class, () -> tools.orderSummary("O-9001"));
    }

    @Test
    @TestSecurity(user = "agent-under-test", roles = "agent")
    void orderSummaryReturnsTheSeededOrderForAnAgentCaller() throws Exception {
        JsonNode body = MAPPER.readTree(tools.orderSummary("O-9001"));
        assertEquals("O-9001", body.path("orderId").asText());
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
    @OidcSecurity(claims = { @Claim(key = "scope", value = "mcp:tools:read") })
    void cancelOrderRejectsAnUnderScopedTokenAndLeavesTheOrderUnchanged() {
        Elicitation elicitation = mock(Elicitation.class);

        assertThrows(ForbiddenException.class, () -> tools.cancelOrder("O-9002", "customer requested", elicitation));

        assertEquals(OrderStatus.PLACED, orders.find("O-9002").status(), "an under-scoped call must not cancel the order");
        verifyNoInteractions(elicitation);
    }

    @Test
    @TestSecurity(user = "agent-under-test", roles = "agent")
    @OidcSecurity(claims = { @Claim(key = "scope", value = "mcp:tools:write") })
    void cancelOrderRejectsAnOrderThatIsAlreadyDelivered() {
        Elicitation elicitation = mock(Elicitation.class);

        ToolCallException failure = assertThrows(ToolCallException.class,
                () -> tools.cancelOrder("O-9005", "customer requested", elicitation));

        assertTrue(messageChain(failure).contains("cannot be cancelled"));
        verifyNoInteractions(elicitation);
    }

    @Test
    @TestSecurity(user = "agent-under-test", roles = "agent")
    @OidcSecurity(claims = { @Claim(key = "scope", value = "mcp:tools:write") })
    void cancelOrderLeavesTheOrderUnchangedAndReturnsRejectedByUserWhenTheUserDeclines() throws Exception {
        Elicitation elicitation = respondingWith(ElicitationResponse.Action.DECLINE);

        JsonNode body = MAPPER.readTree(tools.cancelOrder("O-9003", "customer requested", elicitation));

        assertEquals("O-9003", body.path("orderId").asText());
        assertEquals("REJECTED_BY_USER", body.path("outcome").asText());
        assertTrue(body.path("refundedAmount").isNull(), "a declined cancellation must not report a refund");
        assertEquals(OrderStatus.PLACED, orders.find("O-9003").status(), "a declined cancellation must not write anything");
    }

    @Test
    @TestSecurity(user = "agent-under-test", roles = "agent")
    @OidcSecurity(claims = { @Claim(key = "scope", value = "mcp:tools:write") })
    void cancelOrderCancelsAndRefundsWhenTheUserAccepts() throws Exception {
        Elicitation elicitation = respondingWith(ElicitationResponse.Action.ACCEPT);

        JsonNode body = MAPPER.readTree(tools.cancelOrder("O-9004", "customer requested", elicitation));

        assertEquals("O-9004", body.path("orderId").asText());
        assertEquals("CANCELLED", body.path("outcome").asText());
        assertEquals(30.00, body.path("refundedAmount").asDouble(), 0.001);
        assertEquals(OrderStatus.CANCELLED, orders.find("O-9004").status());
    }

    /** Stubs the {@link Elicitation#requestBuilder()} chain so {@code sendAndAwait()} returns a
     * response with the given action, with no server-initiated round trip. */
    private static Elicitation respondingWith(ElicitationResponse.Action action) {
        ElicitationRequest.Builder builder = mock(ElicitationRequest.Builder.class);
        when(builder.setMessage(anyString())).thenReturn(builder);
        when(builder.addSchemaProperty(anyString(), any())).thenReturn(builder);
        ElicitationRequest request = mock(ElicitationRequest.class);
        when(builder.build()).thenReturn(request);
        ElicitationResponse.Content content = mock(ElicitationResponse.Content.class);
        Meta meta = mock(Meta.class);
        when(request.sendAndAwait()).thenReturn(new ElicitationResponse(action, content, meta));

        Elicitation elicitation = mock(Elicitation.class);
        when(elicitation.requestBuilder()).thenReturn(builder);
        return elicitation;
    }

    /** The instructive text may sit on the thrown {@link ToolCallException} or on its cause;
     * walk the chain so the assertion holds either way (mirrors ch15's PaymentMcpToolsTest). */
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
