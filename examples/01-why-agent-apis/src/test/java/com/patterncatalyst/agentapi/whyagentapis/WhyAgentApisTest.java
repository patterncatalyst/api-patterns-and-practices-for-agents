package com.patterncatalyst.agentapi.whyagentapis;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.patterncatalyst.agentapi.OrderStatus;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the tool and service classes directly, never the assistant: these checks must stay
 * deterministic and need no model or network. The measured, model-driven contrast between the
 * two contracts is captured once in {@code fixtures/} and asserted by verify.sh.
 */
@QuarkusTest
class WhyAgentApisTest {

    @Inject
    OrderOpsService service;

    @Inject
    GoodOrderOpsTools goodTools;

    @Inject
    BadOrderOpsTools badTools;

    @BeforeEach
    void reset() {
        service.reset();
        goodTools.resetRun();
        badTools.resetRun();
    }

    @Test
    void goodContractCancelsAndRefundsInOneCall() {
        String reply = goodTools.cancelAndRefund("O-1007", "changed their mind");

        assertTrue(reply.contains("cancelled"), "instructive success message: " + reply);
        assertEquals(1, goodTools.runCalls());
        assertEquals(0, goodTools.runArgErrors());
        assertEquals(OrderStatus.CANCELLED, service.status("O-1007"));
    }

    @Test
    void goodContractErrorNamesTheProblemAndTheRemedy() {
        String reply = goodTools.cancelAndRefund("O-9999", "does not exist");

        assertTrue(reply.contains("O-9999") && reply.contains("does not exist"),
                "the good contract's error states what is wrong: " + reply);
        assertEquals(1, goodTools.runArgErrors());
    }

    @Test
    void badContractNeedsThreeCallsForTheSameTask() {
        String order = badTools.getOrder("O-1007");
        assertTrue(order.contains("\"status\":\"PAID\""), order);

        String status = badTools.updateOrderStatus("O-1007", "CANCELLED");
        assertTrue(status.contains("CANCELLED"), status);

        String refund = badTools.createRefund("O-1007", 129.99);
        assertTrue(refund.contains("REFUNDED"), refund);

        assertEquals(3, badTools.runCalls());
        assertEquals(0, badTools.runArgErrors());
        assertEquals(OrderStatus.CANCELLED, service.status("O-1007"));
    }

    @Test
    void badContractErrorIsOpaque() {
        String reply = badTools.getOrder("O-9999");

        assertEquals("{\"error\":\"E404\"}", reply);
        assertEquals(1, badTools.runArgErrors());
    }

    @Test
    void badContractRejectsAMismatchedRefundAmountOpaquely() {
        badTools.updateOrderStatus("O-1007", "CANCELLED");

        String reply = badTools.createRefund("O-1007", 1.00);

        assertEquals("{\"error\":\"E400\"}", reply);
    }

    @Test
    void badContractRejectsAnUnrecognizedStatusOpaquely() {
        String reply = badTools.updateOrderStatus("O-1007", "CANCELED");

        assertEquals("{\"error\":\"E400\"}", reply);
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
