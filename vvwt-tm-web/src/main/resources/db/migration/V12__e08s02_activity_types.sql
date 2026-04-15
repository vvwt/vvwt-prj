-- ============================================================
-- V12__e08s02_activity_types.sql — activity_types table
-- Story:  E08S02
-- DECs:   DEC-5  (multi-tenant invariants)
--         DEC-14 (H2 + Flyway persistence)
--         DEC-17 (eager tenant_id materialization)
-- ============================================================
--
-- Creates the activity_types table, which stores organizer-defined
-- activity configurations (e.g., "Mannschaftsfoto") with assignment
-- rules and optional capacity limits per round.
--
-- Assignment algorithm: E08S04.
-- REST/UI: E08S06.
--
-- DEC-17: tenant_id UUID NOT NULL, FK to tenants — eager materialization.
-- DEC-5:  tenant scope enforced at the repository layer (TenantScopedRepository).
--
-- Constraints:
--   UNIQUE (tournament_id, name) — AC5: no duplicate activity names per tournament.
--   CHECK (capacity_per_round IS NULL OR capacity_per_round > 0) — AC6.
--
-- No INSERT statements per DEC-17 / AC9 (no seed data).
-- ============================================================

-- ------------------------------------------------------------
-- activity_types
-- AC1 columns:
--   id                UUID PK
--   tournament_id     UUID NOT NULL FK tournament(id)
--   name              VARCHAR NOT NULL
--   assignment_rule   VARCHAR NOT NULL  (stores rule identifier, e.g. "FIRST_FREE_ROUND")
--   capacity_per_round INT nullable     (NULL = unlimited; NOT NULL must be > 0)
--   sort_order        INT NOT NULL
--   tenant_id         UUID NOT NULL FK tenants(id)
-- ------------------------------------------------------------
CREATE TABLE activity_types (
    id                   UUID          NOT NULL,
    tournament_id        UUID          NOT NULL,
    name                 VARCHAR       NOT NULL,
    assignment_rule      VARCHAR       NOT NULL,
    capacity_per_round   INT,
    sort_order           INT           NOT NULL,
    tenant_id            UUID          NOT NULL,
    CONSTRAINT pk_activity_types
        PRIMARY KEY (id),
    CONSTRAINT fk_activity_types_tournament
        FOREIGN KEY (tournament_id) REFERENCES tournament (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_activity_types_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
        ON DELETE RESTRICT,
    -- AC5: no duplicate activity names per tournament
    CONSTRAINT uq_activity_types_tournament_name
        UNIQUE (tournament_id, name),
    -- AC6: capacity_per_round must be NULL (unlimited) or strictly positive
    CONSTRAINT chk_activity_types_capacity
        CHECK (capacity_per_round IS NULL OR capacity_per_round > 0)
);

-- Fast tenant-scoped queries (DEC-17)
CREATE INDEX idx_activity_types_tenant_id ON activity_types (tenant_id);

-- Fast tournament-scoped queries
CREATE INDEX idx_activity_types_tournament_id ON activity_types (tournament_id);
