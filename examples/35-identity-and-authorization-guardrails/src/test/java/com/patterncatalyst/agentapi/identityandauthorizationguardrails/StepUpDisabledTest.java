package com.patterncatalyst.agentapi.identityandauthorizationguardrails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.patterncatalyst.agentapi.OrderStatus;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The biting negative witness, in-JVM: with {@code guardrails.step-up.enforced=false}
 * ({@link DisabledProfile}), a write-scoped token that carries no acr claim at all, exactly the
 * shape {@link OrderMcpToolsTest#cancelOrderRejectsAWriteScopedTokenBelowTheStepUpFloor} proved
 * Forbidden under the default (enforced) profile, now reaches the mutation. The write-scope check
 * is untouched by the flag and still runs, so this isolates the acr floor, and only the acr floor,
 * as what blocked that same call before. The live, container-level version of this same witness
 * (recreating the compose {@code app} service with {@code STEPUP_ENFORCED=false}, then restoring
 * it) is {@code verify.sh}'s job; this test proves the same guard holds in-process.
 */
@QuarkusTest
@TestProfile(StepUpDisabledTest.DisabledProfile.class)
class StepUpDisabledTest {

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("guardrails.step-up.enforced", "false");
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    OrderMcpTools tools;

    @Inject
    OrderRepository orders;

    @Test
    @TestSecurity(user = "agent-under-test", roles = "agent")
    @OidcSecurity(claims = { @Claim(key = "scope", value = "mcp:tools:write") })
    void aDisabledStepUpFloorLetsAWriteScopedLowAcrTokenReachTheMutation() throws Exception {
        JsonNode body = MAPPER.readTree(tools.cancelOrder("O-9102", "customer requested"));

        assertEquals("CANCELLED", body.path("outcome").asText());
        assertEquals(OrderStatus.CANCELLED, orders.find("O-9102").status(),
                "with step-up enforcement off, a write-scoped token with no acr claim must still cancel");
    }

    @Test
    @TestSecurity(user = "agent-under-test", roles = "agent")
    @OidcSecurity(claims = { @Claim(key = "scope", value = "mcp:tools:read") })
    void theWriteScopeGateStaysIndependentOfTheStepUpFlag() {
        // Disabling step-up enforcement removes only the acr check; a missing write scope is a
        // separate axis and still rejects the call.
        assertThrows(ForbiddenException.class, () -> tools.cancelOrder("O-9103", "customer requested"));
        assertEquals(OrderStatus.PLACED, orders.find("O-9103").status());
    }
}
