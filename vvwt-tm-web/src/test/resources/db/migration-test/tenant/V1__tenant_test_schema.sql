-- Test migration for tenant module (E14S04, AC2, AC3, AC5, AC8 integration tests)
-- Proves that the PerTenantFlywayRunner applies per-module migrations correctly.
CREATE TABLE IF NOT EXISTS tenant_test_marker (
    id   INT PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);
