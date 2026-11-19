-- Deterministic seed: two orders, each with a full set of heavy and internal fields alongside
-- the lean ones the agent-facing BFF projection actually needs.
INSERT INTO orders.orders (
    id, record_uuid, customer_uuid, customer_id, customer_full_name, customer_email,
    customer_risk_score, item_sku, quantity, amount, status, internal_notes, audit_trail
) VALUES (
    'O-1001',
    '11111111-1111-1111-1111-111111111111',
    '22222222-2222-2222-2222-222222222222',
    'CUST-1001',
    'Jordan Rivera',
    'jordan.rivera@example.com',
    42,
    'SKU-7',
    3,
    149.97,
    'PLACED',
    'Flagged for manual review by the fraud-screening batch job.',
    '[{"actor":"fraud-screening-job","action":"flagged","occurredAt":"2026-11-10T08:00:00Z"},'
    '{"actor":"ops-console","action":"order-placed","occurredAt":"2026-11-10T08:05:00Z"}]'::jsonb
), (
    'O-1002',
    '33333333-3333-3333-3333-333333333333',
    '44444444-4444-4444-4444-444444444444',
    'CUST-1002',
    'Avery Chen',
    'avery.chen@example.com',
    7,
    'SKU-3',
    1,
    24.50,
    'PAID',
    'No flags raised.',
    '[{"actor":"ops-console","action":"order-placed","occurredAt":"2026-11-11T09:00:00Z"},'
    '{"actor":"payment-service","action":"payment-processed","occurredAt":"2026-11-11T09:02:00Z"}]'::jsonb
);
