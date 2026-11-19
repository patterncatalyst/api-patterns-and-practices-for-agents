-- O-19001/P-19001: cancellable, used for the pause-then-approve sequence (the gate opens and
-- holds; a redelivered APPROVE applies the cancel-and-refund side effect exactly once).
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-19001', 'CUST-91', 'SKU-4', 1, 149.00, 'PLACED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('P-19001', 'O-19001', 149.00, 'COMPLETED')
ON CONFLICT (id) DO NOTHING;

-- O-19002/P-19002: cancellable, used for the pause-then-reject sequence (REJECT terminates with
-- no side effect, ever).
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-19002', 'CUST-92', 'SKU-5', 2, 75.00, 'PLACED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('P-19002', 'O-19002', 75.00, 'COMPLETED')
ON CONFLICT (id) DO NOTHING;

-- O-19003/P-19003: already CANCELLED (terminal), used for the not-cancellable negative case:
-- the trigger must reject with 409 before ever opening a gate.
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-19003', 'CUST-93', 'SKU-6', 1, 220.00, 'CANCELLED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('P-19003', 'O-19003', 220.00, 'REFUNDED')
ON CONFLICT (id) DO NOTHING;

-- O-19006/P-19006: cancellable, used only by mvn -B verify's approve-exactly-once test (kept
-- separate from O-19001 so JUnit's unspecified method order never lets one test observe another
-- test's side effect on the same order).
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-19006', 'CUST-99', 'SKU-3', 1, 149.00, 'PLACED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('P-19006', 'O-19006', 149.00, 'COMPLETED')
ON CONFLICT (id) DO NOTHING;

-- O-19101..O-19105: cancellable orders used only by mvn -B verify's reasonCode classification
-- tests (MODEL=off, FixtureCancelAdvisor and its keyword-rule fallback).
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-19101', 'CUST-94', 'SKU-7', 1, 60.00, 'PLACED')
ON CONFLICT (id) DO NOTHING;
INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('P-19101', 'O-19101', 60.00, 'COMPLETED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-19102', 'CUST-95', 'SKU-8', 1, 85.00, 'PLACED')
ON CONFLICT (id) DO NOTHING;
INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('P-19102', 'O-19102', 85.00, 'COMPLETED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-19103', 'CUST-96', 'SKU-9', 1, 95.00, 'PLACED')
ON CONFLICT (id) DO NOTHING;
INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('P-19103', 'O-19103', 95.00, 'COMPLETED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-19104', 'CUST-97', 'SKU-1', 1, 65.00, 'PLACED')
ON CONFLICT (id) DO NOTHING;
INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('P-19104', 'O-19104', 65.00, 'COMPLETED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-19105', 'CUST-98', 'SKU-2', 1, 40.00, 'PLACED')
ON CONFLICT (id) DO NOTHING;
INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('P-19105', 'O-19105', 40.00, 'COMPLETED')
ON CONFLICT (id) DO NOTHING;
