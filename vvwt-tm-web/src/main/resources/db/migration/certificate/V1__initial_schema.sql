-- ============================================================
-- certificate/V1__initial_schema.sql — Certificate template table (certificate module)
-- Story:  E23S06 (per-module migration, DEC-25)
-- DECs:   DEC-14 (H2+Flyway), DEC-20 (DB-per-Tenant), DEC-21 (per-module migration path),
--         DEC-25 (Wave-2 Big-Bang-Reset; root V15 retained until E23S10 Cutover-2)
-- ============================================================
--
-- Schema parity (AC-FLYWAY-PER-MODULE-V1): This file is content-equivalent to the legacy
-- root-level V15__e12s04_certificate_template.sql, which it replaces as the per-module
-- source once E23S10 performs Cutover-2 (deletion of V15 root migration).
-- During this parallel phase both files coexist on disk; only this per-module path is applied
-- by the PerTenantFlywayRunner against per-tenant H2 databases. The root V15 is applied by
-- Spring Boot Flyway against the shared application DataSource (filtered by
-- FlywayRootMigrationsCustomizer to root-level only — never sees this sub-directory).
--
-- Coexistence safety (AC-FLYWAY-SCHEMA-COEXISTS):
-- The PerTenantFlywayRunner applies ONLY per-module paths (classpath:db/migration/{module}).
-- The root V15__*.sql targets the SHARED application DataSource, not per-tenant H2 databases.
-- The two migrations target DIFFERENT databases; they do NOT both run against the same database.
-- IF NOT EXISTS is included as a defensive guard for the Big-Bang-Reset transition (DEC-25):
-- if for any reason a tenant DB already has the table (e.g., from a prior partial run), the
-- CREATE TABLE does not fail.
--
-- This migration creates the certificate_template table.

CREATE TABLE IF NOT EXISTS certificate_template (
    tournament_id    UUID         NOT NULL,
    filename         VARCHAR(255) NOT NULL,
    format           VARCHAR(10)  NOT NULL,   -- 'html' or 'svg'
    upload_timestamp TIMESTAMP    NOT NULL,
    file_size_bytes  BIGINT       NOT NULL,
    CONSTRAINT pk_certificate_template PRIMARY KEY (tournament_id),
    CONSTRAINT fk_certificate_template_tournament
        FOREIGN KEY (tournament_id) REFERENCES tournament (id) ON DELETE CASCADE
);
