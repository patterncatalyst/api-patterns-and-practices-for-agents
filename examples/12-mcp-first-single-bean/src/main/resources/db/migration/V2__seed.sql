-- One seeded order, read identically by all three faces (REST, GraphQL, MCP).
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-9001', 'CUST-77', 'SKU-4', 3, 88.50, 'PLACED')
ON CONFLICT (id) DO NOTHING;
