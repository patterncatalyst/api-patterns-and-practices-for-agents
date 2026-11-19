package com.patterncatalyst.agentapi.longrunningagentworkflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.patterncatalyst.agentapi.Order;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.Test;

/**
 * The negative witness at the unit level: with {@code fulfillment.compensation.enabled=false}
 * (demo code, {@code COMPENSATE=off} in compose), a declined payment still fails the saga, but the
 * stock {@code reserveStock} took is never released, so a partial effect of the failed run
 * survives. {@code verify.sh} reproduces the same bite against the live container.
 */
@QuarkusTest
@TestProfile(CompensationDisabledProfile.class)
class CompensationDisabledTest {

    private static final String SKU_A = "SKU-20-A";

    @Inject
    ProducerTemplate producer;

    @Inject
    FulfillmentService service;

    @Test
    void withCompensationDisabledADeclinedPaymentLeavesTheStockReserved() {
        String id = "O-20099";
        Integer before = service.stockOf(SKU_A);

        try {
            producer.sendBodyAndHeader("direct:fulfillOrder", new Order(id, "CU-20", SKU_A, 1, new BigDecimal("900.00")),
                    "orderId", id);
        } catch (RuntimeException expected) {
            // The payment is still declined; only the compensation that would undo the reservation
            // is disabled.
        }

        assertEquals("STOCK_RESERVED", service.statusOf(id), "the compensation never ran, so the step never unwound");
        assertEquals(before - 1, service.stockOf(SKU_A), "the reserved unit was never given back: a partial effect");
        assertNull(service.paymentStatusOf(id), "the payment was still declined");
    }
}
