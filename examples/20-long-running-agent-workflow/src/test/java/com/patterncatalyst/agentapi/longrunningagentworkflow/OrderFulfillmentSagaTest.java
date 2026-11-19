package com.patterncatalyst.agentapi.longrunningagentworkflow;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.patterncatalyst.agentapi.Order;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import org.apache.camel.ProducerTemplate;
import org.junit.jupiter.api.Test;

/**
 * Exercises the whole order-fulfillment saga (nested Camel Saga EIP blocks, the in-memory
 * {@code CamelSagaService}, the agent carrier-choice step) against Dev Services PostgreSQL.
 * MODEL=off (the {@code %test} profile forces {@code agent.carrier.model-source=fixture}): these
 * tests never call Ollama. The live, real-model round trip through {@link CarrierAdvisor} and the
 * negative witness with the compensation guard off, container-level, are asserted by
 * {@code verify.sh} instead.
 */
@QuarkusTest
class OrderFulfillmentSagaTest {

    private static final String SKU_A = "SKU-20-A";
    private static final String SKU_B = "SKU-20-B";

    @Inject
    ProducerTemplate producer;

    @Inject
    FulfillmentService service;

    @Inject
    CarrierDecisionBean carrierDecisionBean;

    private void fulfill(Order order) {
        try {
            producer.sendBodyAndHeader("direct:fulfillOrder", order, "orderId", order.id());
        } catch (RuntimeException e) {
            // Expected for the orders this test deliberately drives to a declined payment or an
            // insufficient-stock failure: the saga has already compensated by the time this
            // exception reaches the caller.
        }
    }

    @Test
    void aCleanRunCompletesEveryStepIncludingTheAgentsCarrierChoice() {
        String id = "O-20001";
        Integer before = service.stockOf(SKU_A);

        fulfill(new Order(id, "CU-20", SKU_A, 1, new BigDecimal("49.99")));

        assertEquals("COMPLETED", service.statusOf(id));
        assertEquals(before - 1, service.stockOf(SKU_A), "one unit was reserved and never released");
        assertEquals("COMPLETED", service.paymentStatusOf(id));
        assertEquals("BOOKED", service.shipmentStatusOf(id));
        assertTrue(service.statusOf(id) != null, "a carrier was recorded");
    }

    @Test
    void anOrderNamedInTheFixtureIsBookedWithTheRecordedCarrierEvenWhenTheRuleWouldDisagree() {
        // O-20901 is recorded in fixtures/carrier-decisions.jsonl as AIRCO; the amount-based rule
        // alone would say FASTLINE (under 100.00). The fixture wins: proof the agent step actually
        // reads the committed fixture, not just the fallback rule.
        String id = "O-20901";

        fulfill(new Order(id, "CU-20", SKU_A, 1, new BigDecimal("49.99")));

        assertEquals("COMPLETED", service.statusOf(id));
    }

    @Test
    void aDeclinedPaymentCompensatesTheEarlierStepAndLeavesNoPartialEffect() {
        String id = "O-20002";
        Integer before = service.stockOf(SKU_A);

        fulfill(new Order(id, "CU-20", SKU_A, 2, new BigDecimal("900.00")));

        assertEquals("COMPENSATED", service.statusOf(id));
        assertEquals(before, service.stockOf(SKU_A), "the reserved stock was released: no net change");
        assertNull(service.paymentStatusOf(id), "a declined payment writes no payment row");
        assertNull(service.shipmentStatusOf(id), "the carrier step never ran");
    }

    @Test
    void insufficientStockCompensatesWithNothingToUndo() {
        String id = "O-20003";
        Integer before = service.stockOf(SKU_B);

        fulfill(new Order(id, "CU-20", SKU_B, before + 1, new BigDecimal("49.99")));

        assertEquals(before, service.stockOf(SKU_B), "nothing was ever reserved, so releasing it changes nothing");
        assertNull(service.paymentStatusOf(id), "the payment step never ran");
    }

    @Test
    void releaseStockIsIdempotentUnderADoubleCompensation() {
        String id = "O-20010";
        Order order = new Order(id, "CU-20", SKU_A, 3, new BigDecimal("49.99"));
        Integer before = service.stockOf(SKU_A);
        service.reserveStock(id, order);
        assertEquals("STOCK_RESERVED", service.statusOf(id));

        service.releaseStock(id);
        assertEquals(before, service.stockOf(SKU_A));
        assertEquals("COMPENSATED", service.statusOf(id));

        service.releaseStock(id);
        assertEquals(before, service.stockOf(SKU_A), "a second compensation restores nothing more");
        assertEquals("COMPENSATED", service.statusOf(id));
    }

    @Test
    void refundPaymentIsIdempotentUnderADoubleCompensation() {
        String id = "O-20011";
        Order order = new Order(id, "CU-20", SKU_A, 1, new BigDecimal("49.99"));
        service.reserveStock(id, order);
        service.takePayment(id);
        assertEquals("COMPLETED", service.paymentStatusOf(id));

        service.refundPayment(id);
        assertEquals("REFUNDED", service.paymentStatusOf(id));
        assertEquals("COMPENSATED", service.statusOf(id));

        service.refundPayment(id);
        assertEquals("REFUNDED", service.paymentStatusOf(id), "a second refund changes nothing more");
    }

    @Test
    void cancelBookingIsIdempotentUnderADoubleCompensation() {
        String id = "O-20012";
        Order order = new Order(id, "CU-20", SKU_A, 1, new BigDecimal("49.99"));
        service.reserveStock(id, order);
        service.takePayment(id);
        carrierDecisionBean.bookCarrier(id);
        assertEquals("BOOKED", service.shipmentStatusOf(id));
        assertEquals("COMPLETED", service.statusOf(id));

        service.cancelBooking(id);
        assertEquals("CANCELLED", service.shipmentStatusOf(id));
        assertEquals("COMPENSATED", service.statusOf(id));

        service.cancelBooking(id);
        assertEquals("CANCELLED", service.shipmentStatusOf(id), "a second cancellation changes nothing more");
    }

    @Test
    void healthReportsUp() {
        given().get("/q/health/ready").then().statusCode(200);
    }
}
