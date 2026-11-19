-- A deterministic order already in a terminal-adjacent state, so cancelling it demonstrates a
-- 409 whose remedy names a non-empty set of allowed transitions (SHIPPED can still move to
-- DELIVERED), not just an empty one.
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-2002', 'CUST-2002', 'SKU-9', 1, 89.00, 'SHIPPED');
