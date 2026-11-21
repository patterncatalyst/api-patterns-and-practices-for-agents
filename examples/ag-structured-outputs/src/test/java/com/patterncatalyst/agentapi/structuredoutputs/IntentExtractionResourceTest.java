package com.patterncatalyst.agentapi.structuredoutputs;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.oneOf;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * MODEL=off (the {@code %test} profile forces {@code agent.extraction.model-source=fixture}):
 * these tests never call Ollama. {@link FixtureIntentSource} replays what each live path's own
 * shape guarantee looks like, so the downstream-parse wiring (not Ollama's own grammar-constrained
 * decoding, which only a live run against the real model can exercise) is what this class proves.
 * The live shape-versus-drift contrast across repeated calls to the real model is {@code
 * verify.sh}'s job instead.
 */
@QuarkusTest
class IntentExtractionResourceTest {

    private static final String MESSAGE =
            "Hi, this is about order O-4821. It arrived two days late and one item was damaged, "
                    + "so I'd like a refund of $38.50 please.";

    @Test
    void typedPathParsesCleanlyOnEveryFixtureCall() {
        given().contentType("text/plain").body(MESSAGE)
                .when().post("/extract/typed")
                .then().statusCode(200)
                .body("path", is("typed"))
                .body("structuredOutputEnabled", is(true))
                .body("parseOk", is(true))
                .body("parsed.orderId", is("O-4821"))
                .body("parsed.intent", oneOf("STATUS_INQUIRY", "REFUND_REQUEST", "CANCEL_REQUEST",
                        "COMPLAINT", "OTHER"));
    }

    @Test
    void untypedPathReportsTheFixtureDriftItReplays() {
        // FixtureIntentSource.untypedRaw wraps the same information the way a small instruct
        // model commonly wraps an answer it was never grammar-constrained to format: markdown
        // fences and a one-line preamble. A strict parse of the whole response text rejects it.
        given().contentType("text/plain").body(MESSAGE)
                .when().post("/extract/untyped")
                .then().statusCode(200)
                .body("path", is("untyped"))
                .body("parseOk", is(false))
                .body("parsed", nullValue());
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
