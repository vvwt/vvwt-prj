-- ============================================================
-- V1__initial_schema.sql — Tournament Manager initial schema
-- Story:  E02S03
-- DECs:   DEC-5 (multi-tenant invariants), DEC-14 (H2+Flyway),
--         DEC-17 (eager schema; no seed data)
-- ============================================================
--
-- This migration creates the foundational multi-tenant schema:
--   - tenants table   (DEC-5, DEC-17)
--   - locations table (DEC-5, DEC-17)
--
-- No INSERT statements are present.
-- The default-tenant row is created at first instance startup
-- by application bootstrap code in E02S04, per DEC-17 amendment
-- (2026-04-12): the default-tenant UUID must be generated per
-- instance to avoid collisions across self-host deployments.
--
-- DEC-5 invariants enforced here:
--
--   (a) Every tenant has at least one location (AC2):
--       CHECK (tenant_location_count >= 1) on tenants
--
--   (b) At most one row may have is_default = TRUE (AC4):
--       H2 2.3.232 does NOT support partial/filtered unique indexes
--       (CREATE UNIQUE INDEX ... WHERE <condition>). Tested at delivery time.
--       Resolution: a generated column `default_sentinel` is ALWAYS NULL for
--       non-default tenants (FALSE) and TRUE for the default tenant. A plain
--       unique index on `default_sentinel` allows unlimited NULL rows (SQL-standard:
--       NULLs are not considered equal in UNIQUE constraints) but at most one TRUE.
--       This is equivalent to a partial unique index and enforces the invariant
--       at the database level — no application-level guard needed for this invariant.
--
--   (c) Default-tenant single-location constraint (AC5):
--       Enforcing "default tenant <= 1 location" requires cross-table logic (does
--       the referenced tenant have is_default=TRUE?). H2 does not support
--       cross-table CHECK constraints or clean BEFORE INSERT triggers at the
--       DDL level. Enforcement is DEFERRED to application-level guard code in
--       E02S04 bootstrap, per AC5 deferral clause. The schema makes the guard
--       possible: is_default is queryable from locations via the FK on tenant_id.
-- ============================================================

-- ------------------------------------------------------------
-- tenants
-- AC2 columns (mandatory):
--   id                    UUID primary key
--   display_name          VARCHAR(255) NOT NULL
--   tenant_location_count INT  NOT NULL  CHECK >= 1
--   is_default            BOOLEAN NOT NULL DEFAULT FALSE
--   created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
--
-- Additional column for AC4 enforcement:
--   default_sentinel      BOOLEAN GENERATED ALWAYS AS — NULL for non-default,
--                         TRUE for default; unique index enforces at-most-one.
-- ------------------------------------------------------------
CREATE TABLE tenants (
    id                    UUID          NOT NULL,
    display_name          VARCHAR(255)  NOT NULL,
    tenant_location_count INT           NOT NULL  CHECK (tenant_location_count >= 1),
    is_default            BOOLEAN       NOT NULL  DEFAULT FALSE,
    created_at            TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    -- AC4: generated sentinel column — NULL for non-default tenants, TRUE for the
    -- default tenant. The unique index below enforces at-most-one TRUE row while
    -- allowing unlimited FALSE (NULL sentinel) rows, because SQL UNIQUE constraints
    -- treat NULLs as distinct values (no two NULLs are considered equal).
    default_sentinel      BOOLEAN       GENERATED ALWAYS AS (CASE WHEN is_default THEN TRUE ELSE NULL END),
    CONSTRAINT pk_tenants PRIMARY KEY (id)
);

-- AC4: at most one row may have is_default = TRUE.
-- Enforced via a unique index on the generated default_sentinel column.
-- NULLs (non-default tenants) are not constrained; only TRUE is unique.
CREATE UNIQUE INDEX idx_tenants_single_default ON tenants (default_sentinel);

-- ------------------------------------------------------------
-- locations
-- AC3 columns (mandatory):
--   id           UUID primary key
--   tenant_id    UUID NOT NULL  (FK -> tenants.id ON DELETE RESTRICT)
--   display_name VARCHAR(255) NOT NULL
--   created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
-- ------------------------------------------------------------
CREATE TABLE locations (
    id           UUID         NOT NULL,
    tenant_id    UUID         NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_locations PRIMARY KEY (id),
    CONSTRAINT fk_locations_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
        ON DELETE RESTRICT
);

-- AC7: non-unique index on locations(tenant_id) for fast tenant-scoped queries.
CREATE INDEX idx_locations_tenant_id ON locations (tenant_id);
