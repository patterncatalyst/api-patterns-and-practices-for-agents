-- One seeded, already-COMPLETED payment for O-9001, so RefundPayment has something to refund
-- without a prior TakePayment call. verify.sh exercises TakePayment against a fresh order and
-- RefundPayment against this seed, both through the separate payment-service image.
INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('PAY-9001', 'O-9001', 49.99, 'COMPLETED')
ON CONFLICT (order_id) DO NOTHING;
