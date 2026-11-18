-- One order PLACED with its payment COMPLETED: a cancellable order with money already taken,
-- so cancel-and-refund has real work to do on both sides.
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-5001', 'CUST-42', 'SKU-9', 1, 149.00, 'PLACED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('P-5001', 'O-5001', 149.00, 'COMPLETED')
ON CONFLICT (id) DO NOTHING;
