-- Two seeded orders in the orders schema, read only by the order server:
-- O-7001 has shipped (not cancellable; the shipping server also has a row for it).
-- O-7002 is still PLACED (cancellable; nothing in the shipping schema yet, since it has not
-- shipped), confirming the two schemas stay independently owned.
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-7001', 'CUST-88', 'SKU-7', 2, 45.00, 'SHIPPED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-7002', 'CUST-21', 'SKU-3', 1, 19.99, 'PLACED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO shipping.shipments (id, order_id, carrier, tracking_number, status)
VALUES ('SHIP-7001', 'O-7001', 'FASTLINE', 'FL-440219', 'IN_TRANSIT')
ON CONFLICT (id) DO NOTHING;
