-- Test-only schema for MarkerDao reference test (E16S01 AC9).
-- This file lives in src/test/resources — it is NOT a production Flyway migration.
-- Used by MarkerDaoTest to demonstrate TenantDaoTestSupport three-rule compliance.
-- Clearly labelled to prevent confusion with production migrations.

CREATE TABLE IF NOT EXISTS marker (
    id   VARCHAR(36)  NOT NULL,
    note VARCHAR(255) NOT NULL,
    CONSTRAINT pk_marker PRIMARY KEY (id)
);
