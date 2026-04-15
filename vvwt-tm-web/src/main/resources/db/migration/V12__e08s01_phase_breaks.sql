-- ============================================================
-- V12__e08s01_phase_breaks.sql — E08S01 AC2: phase_breaks table
-- Story:  E08S01
-- DECs:   DEC-5  (multi-tenant invariants, tenant_id NOT NULL)
--         DEC-14 (H2 + Flyway persistence)
--         DEC-17 (eager schema; tenant_id NOT NULL; no seed data)
-- ============================================================
--
-- Creates the phase_breaks table for intra-phase break configuration.
-- A PhaseBreak represents a pause that occurs in the timeline between two
-- adjacent laps within a phase (e.g., a 40-minute lunch break after round 4).
--
-- AC2 (schema): id UUID PK, phase_id FK, after_lap_number INT, duration_minutes INT,
--               label VARCHAR nullable, tenant_id UUID FK.
-- AC2 (uniqueness): UNIQUE (phase_id, after_lap_number) — at most one break per lap
--                   boundary per phase.
-- AC2 (validation): CHECK (duration_minutes > 0) — break must have positive duration.
-- AC7 (tenant scope): tenant_id NOT NULL with FK to tenants, index for filtered queries.
-- AC9 (migration safety): new table; no existing data affected.
--
-- No INSERT statements per DEC-17 / AC9.
-- All FK references use ON DELETE RESTRICT to preserve data integrity.
-- ============================================================

CREATE TABLE phase_breaks (
    id                  UUID          NOT NULL,
    phase_id            UUID          NOT NULL,
    after_lap_number    INT           NOT NULL,
    duration_minutes    INT           NOT NULL,
    label               VARCHAR       NULL,
    tenant_id           UUID          NOT NULL,

    CONSTRAINT pk_phase_breaks
        PRIMARY KEY (id),

    -- FK to phase — a break belongs to exactly one phase
    CONSTRAINT fk_phase_breaks_phase
        FOREIGN KEY (phase_id) REFERENCES phase (id)
        ON DELETE RESTRICT,

    -- Tenant scope per DEC-5 / DEC-17
    CONSTRAINT fk_phase_breaks_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
        ON DELETE RESTRICT,

    -- AC2: at most one break per lap boundary per phase
    CONSTRAINT uq_phase_breaks_phase_lap
        UNIQUE (phase_id, after_lap_number),

    -- AC2: duration must be positive
    CONSTRAINT chk_phase_breaks_duration
        CHECK (duration_minutes > 0)
);

-- Tenant-scoped filter per DEC-5 / DEC-17
CREATE INDEX idx_phase_breaks_tenant_id ON phase_breaks (tenant_id);

-- Phase-lookup index for timeline calculation (E08S03)
CREATE INDEX idx_phase_breaks_phase_id ON phase_breaks (phase_id);
