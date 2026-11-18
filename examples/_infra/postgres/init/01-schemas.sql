-- One database per CLAUDE.md's "one database each" shape, one schema per service.
-- No tables here: each example's own Flyway migrations own its schema's tables.
CREATE SCHEMA IF NOT EXISTS orders;
CREATE SCHEMA IF NOT EXISTS inventory;
CREATE SCHEMA IF NOT EXISTS payments;
CREATE SCHEMA IF NOT EXISTS shipping;
CREATE SCHEMA IF NOT EXISTS notifications;
