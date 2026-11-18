-- 60 deterministic orders, varied across 4 SKUs, 5 statuses and 6 customers, so a search's
-- expected matches and a paging sweep's expected page count are both computable, not guessed.
-- n=5,25,45 are the only rows where status=PLACED and item_sku=SKU-2 (the search example's
-- fixture case): n % 5 = 0 selects PLACED, and of those, n % 4 = 1 selects SKU-2.
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
SELECT
    'O-' || lpad(n::text, 4, '0'),
    'CUST-' || (1 + (n % 6)),
    'SKU-' || (1 + (n % 4)),
    1 + (n % 3),
    (20 + (n * 7) % 180)::numeric(12, 2),
    (ARRAY['PLACED', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED'])[1 + (n % 5)]
FROM generate_series(1, 60) AS n;
