package com.patterncatalyst.agentapi.mcpfacade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.patterncatalyst.agentapi.payment.v1.Payment;
import com.patterncatalyst.agentapi.payment.v1.PaymentStatus;
import org.junit.jupiter.api.Test;

/**
 * The reshape in isolation, both directions, with no Quarkus context and no running gRPC channel:
 * a JSON decimal amount to proto3 int64 cents on the way in, and cents plus a {@link
 * PaymentStatus} back to decimal JSON on the way out.
 */
class AmountReshapeTest {

    @Test
    void toCentsReshapesADecimalStringToCents() {
        assertEquals(4999, AmountReshape.toCents("49.99"));
    }

    @Test
    void toCentsRoundsAShorterDecimalUpToTwoPlaces() {
        assertEquals(4990, AmountReshape.toCents("49.9"));
        assertEquals(10000, AmountReshape.toCents("100"));
    }

    @Test
    void toCentsRejectsAZeroOrNegativeAmount() {
        assertThrows(IllegalArgumentException.class, () -> AmountReshape.toCents("0"));
        assertThrows(IllegalArgumentException.class, () -> AmountReshape.toCents("-5.00"));
    }

    @Test
    void toCentsRejectsAMalformedDecimal() {
        assertThrows(NumberFormatException.class, () -> AmountReshape.toCents("not-a-number"));
    }

    @Test
    void toDecimalReshapesCentsBackToADecimalString() {
        assertEquals("49.99", AmountReshape.toDecimal(4999));
        assertEquals("100.00", AmountReshape.toDecimal(10000));
    }

    @Test
    void toCentsAndToDecimalRoundTrip() {
        long cents = AmountReshape.toCents("123.45");
        assertEquals("123.45", AmountReshape.toDecimal(cents));
    }

    @Test
    void toViewReshapesAProtoPaymentIntoLeanDecimalJsonFields() {
        Payment payment = Payment.newBuilder()
                .setId("PAY-9001")
                .setOrderId("O-9001")
                .setAmountCents(4999)
                .setStatus(PaymentStatus.COMPLETED)
                .build();

        PaymentView view = AmountReshape.toView(payment);

        assertEquals("O-9001", view.orderId());
        assertEquals("49.99", view.amount());
        assertEquals("COMPLETED", view.status());
    }
}
