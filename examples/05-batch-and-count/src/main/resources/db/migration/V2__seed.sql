-- Deterministic seed: n in 1..36. status cycles every 4 (PLACED, PAID, SHIPPED, DELIVERED), so
-- each status has exactly 9 rows; GET /orders/count?status=... is therefore a known, checkable
-- number without needing a second query to compute it by hand.
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
SELECT
    'O-' || lpad(n::text, 4, '0'),
    'CUST-' || ((n % 7) + 1),
    'SKU-' || ((n % 4) + 1),
    ((n % 3) + 1),
    (n * 10)::numeric(12, 2),
    CASE n % 4
        WHEN 0 THEN 'PLACED'
        WHEN 1 THEN 'PAID'
        WHEN 2 THEN 'SHIPPED'
        ELSE 'DELIVERED'
    END
FROM generate_series(1, 36) AS n
ON CONFLICT (id) DO NOTHING;
