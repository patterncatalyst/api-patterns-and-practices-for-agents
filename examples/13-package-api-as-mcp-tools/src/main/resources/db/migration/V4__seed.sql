-- One seeded order with its payment and shipment, read separately by paymentStatus and
-- shipmentTracking, and composed into one response by orderStatus.
INSERT INTO orders.orders (id, customer_id, item_sku, quantity, amount, status)
VALUES ('O-9001', 'CUST-77', 'SKU-4', 3, 88.50, 'SHIPPED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO payments.payments (id, order_id, amount, status)
VALUES ('PAY-9001', 'O-9001', 88.50, 'COMPLETED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO shipping.shipments (id, order_id, carrier, tracking_number, status)
VALUES ('SHIP-9001', 'O-9001', 'FASTLINE', 'FL-778821', 'IN_TRANSIT')
ON CONFLICT (id) DO NOTHING;
