-- One row per test that might mutate it, so tests stay independent of run order: O-9001 is read
-- only (orderSummary, the role check); O-9002 through O-9004 are each cancelled by at most one
-- test; O-9005 is already DELIVERED, a state cancelOrder must refuse to move out of.
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status) VALUES
    ('O-9001', 'CUST-77', 'SKU-4', 2, 49.99, 'PLACED'),
    ('O-9002', 'CUST-78', 'SKU-5', 1, 75.00, 'PLACED'),
    ('O-9003', 'CUST-79', 'SKU-6', 4, 120.00, 'PLACED'),
    ('O-9004', 'CUST-80', 'SKU-7', 1, 30.00, 'PLACED'),
    ('O-9005', 'CUST-81', 'SKU-8', 1, 15.00, 'DELIVERED')
ON CONFLICT (id) DO NOTHING;
