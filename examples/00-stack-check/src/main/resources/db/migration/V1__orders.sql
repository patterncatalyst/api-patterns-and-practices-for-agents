-- One small table in the orders schema, proving schema-per-service Flyway init.
CREATE TABLE IF NOT EXISTS stack_check (
    id   varchar(64) PRIMARY KEY,
    note varchar(255) NOT NULL
);
