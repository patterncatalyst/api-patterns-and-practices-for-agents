-- O-5001/P-5001: a cancellable order with money already taken, used for the dry-run-then-commit
-- sequence (the dry run must leave this pair untouched before the real commit runs).
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-5001', 'CUST-42', 'SKU-9', 1, 149.00, 'PLACED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('P-5001', 'O-5001', 149.00, 'COMPLETED')
ON CONFLICT (id) DO NOTHING;

-- O-5002/P-5002: used for the REJECTED_BY_USER no-op check; stays cancellable afterwards.
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-5002', 'CUST-43', 'SKU-3', 2, 75.00, 'PLACED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('P-5002', 'O-5002', 75.00, 'COMPLETED')
ON CONFLICT (id) DO NOTHING;

-- O-5003/P-5003: used for a plain commit (no dry run, no decision).
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-5003', 'CUST-44', 'SKU-7', 1, 200.00, 'PLACED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('P-5003', 'O-5003', 200.00, 'COMPLETED')
ON CONFLICT (id) DO NOTHING;

-- O-5004/P-5004: already CANCELLED (terminal), used for the not-cancellable negative case.
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-5004', 'CUST-45', 'SKU-1', 1, 50.00, 'CANCELLED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('P-5004', 'O-5004', 50.00, 'REFUNDED')
ON CONFLICT (id) DO NOTHING;
