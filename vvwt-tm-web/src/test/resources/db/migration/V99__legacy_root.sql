-- Legacy root migration that must NOT be applied by PerTenantFlywayRunner (AC8, E14S04)
-- If the runner accidentally scans the root db/migration path, this table would be created.
-- Its absence proves the runner uses only per-module paths.
CREATE TABLE IF NOT EXISTS legacy_root_marker (id INT PRIMARY KEY);
