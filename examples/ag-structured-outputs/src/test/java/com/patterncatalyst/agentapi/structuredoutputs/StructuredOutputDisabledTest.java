package com.patterncatalyst.agentapi.structuredoutputs;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The negative witness, in the fixture lane: {@code agent.extraction.structured-output-enabled}
 * forced to {@code false} routes {@code /extract/typed} through the exact same unconstrained
 * fixture {@code /extract/untyped} always uses, so the one endpoint that otherwise guarantees a
 * parseable {@link ExtractedIntent} now returns 422, proving the schema constraint, not the system
 * message or anything else the two AiServices share, is what was holding the shape together. The
 * live, container-level version of this same witness (recreating the compose {@code app} service
 * with {@code AGENT_EXTRACTION_STRUCTURED_OUTPUT_ENABLED=false}, then restoring it) is {@code
 * verify.sh}'s job; this test proves the same guard holds in-process, MODEL=off.
 */
@QuarkusTest
@TestProfile(StructuredOutputDisabledTest.DisabledProfile.class)
class StructuredOutputDisabledTest {

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("agent.extraction.structured-output-enabled", "false");
        }
    }

    private static final String MESSAGE =
            "Hi, this is about order O-4821. It arrived two days late and one item was damaged, "
                    + "so I'd like a refund of $38.50 please.";

    @Test
    void theTypedEndpointFailsToParseOnceItsOwnSchemaConstraintIsTurnedOff() {
        given().contentType("text/plain").body(MESSAGE)
                .when().post("/extract/typed")
                .then().statusCode(422)
                .body("path", is("typed"))
                .body("structuredOutputEnabled", is(false))
                .body("parseOk", is(false))
                .body("parsed", nullValue());
    }
}
