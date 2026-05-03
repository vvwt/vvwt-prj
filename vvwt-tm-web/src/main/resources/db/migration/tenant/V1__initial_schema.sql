-- ============================================================
-- tenant/V1__initial_schema.sql — Tenant context schema
-- Epic:  E45 (Wave-2 Big-Bang-Reset)
-- Story: E45S05
-- DECs:  DEC-20 (DB-per-Tenant: one H2 file per tenant; per-tenant file is
--                the ownership statement — no tenant_id discriminator column)
--        DEC-25 (Wave-2 Big-Bang-Reset: single atomic commit, reconstructed-from-scratch)
--        DEC-39 D1 (tenant_id removed from tenant-scoped tables)
--        DEC-39 D4 (per-tenant file membership is the ownership statement;
--                   locations.tenant_id FK dropped — no cross-file FK needed)
--        DEC-5  (tenants invariants: at-least-one-location, at-most-one-default)
--        DEC-17 (amended by DEC-39: tenant_id NOT NULL rule lifted for DEC-39 D1 tables)
-- ============================================================
--
-- Replaces root V1__initial_schema.sql (tenants + locations portion).
-- Reconstructed from scratch per DEC-25 §Decision consolidation-vs-mutation invariant.
--
-- Key differences from root V1:
--   - locations.tenant_id column DROPPED (DEC-39 D1 + D4)
--   - fk_locations_tenant FK DROPPED
--   - idx_locations_tenant_id index DROPPED
--   - No FK from locations to tenants (per-tenant file boundary is ownership)
--
-- Tables:
--   tenants   — tenant registry (one row per tenant in the per-tenant H2 file)
--   locations — location registry (rows implicitly belong to this file's tenant)
-- ============================================================

-- ------------------------------------------------------------
-- tenants
-- DEC-5 invariants enforced here:
--   (a) Every tenant has at least one location:
--       CHECK (tenant_location_count >= 1) on tenants
--   (b) At most one row may have is_default = TRUE:
--       Generated column default_sentinel + UNIQUE index
--       (H2 2.x: NULLs are distinct in UNIQUE — allows unlimited non-default rows)
-- ------------------------------------------------------------
CREATE TABLE tenants (
    id                    UUID          NOT NULL,
    display_name          VARCHAR(255)  NOT NULL,
    tenant_location_count INT           NOT NULL  CHECK (tenant_location_count >= 1),
    is_default            BOOLEAN       NOT NULL  DEFAULT FALSE,
    created_at            TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    -- DEC-5 AC4: generated sentinel — NULL for non-default, TRUE for default.
    -- The UNIQUE index allows unlimited NULL rows (SQL standard: NULLs are distinct)
    -- but enforces at most one TRUE row.
    default_sentinel      BOOLEAN       GENERATED ALWAYS AS (CASE WHEN is_default THEN TRUE ELSE NULL END),
    CONSTRAINT pk_tenants PRIMARY KEY (id)
);

-- DEC-5 AC4: at most one default tenant.
CREATE UNIQUE INDEX idx_tenants_single_default ON tenants (default_sentinel);

-- ------------------------------------------------------------
-- locations
-- DEC-39 D1 + D4: tenant_id column DROPPED.
-- Ownership is expressed by per-tenant H2 file membership (DEC-20).
-- No FK to tenants is required — all rows in this file belong to this file's tenant.
-- ------------------------------------------------------------
CREATE TABLE locations (
    id           UUID         NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    created_at   TIMESTAMP    NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_locations PRIMARY KEY (id)
);
