-- One row per test or live check that might mutate it, so tests stay independent of run order:
-- O-9101 is read only (orderSummary, the role check); O-9102 through O-9103 are rejection targets
-- that must stay unchanged (missing write scope, write scope below the step-up acr floor);
-- O-9104 is already DELIVERED, a state cancelOrder must refuse to move out of regardless of
-- credential strength; O-9105 is the one row a fully-cleared write-scope-plus-step-up-acr call
-- actually cancels and refunds.
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status) VALUES
    ('O-9101', 'CUST-91', 'SKU-11', 2, 49.99, 'PLACED'),
    ('O-9102', 'CUST-92', 'SKU-12', 1, 75.00, 'PLACED'),
    ('O-9103', 'CUST-93', 'SKU-13', 3, 90.00, 'PLACED'),
    ('O-9104', 'CUST-94', 'SKU-14', 1, 15.00, 'DELIVERED'),
    ('O-9105', 'CUST-95', 'SKU-15', 1, 30.00, 'PLACED')
ON CONFLICT (id) DO NOTHING;
