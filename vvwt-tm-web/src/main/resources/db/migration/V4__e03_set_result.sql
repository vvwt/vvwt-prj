-- ============================================================
-- V4__e03_set_result.sql — Tournament Manager E03 SetResult entity
-- Story:  E03S03
-- DECs:   DEC-5  (multi-tenant invariants, tenant_id NOT NULL)
--         DEC-7  (fresh rewrite — no legacy data migration)
--         DEC-14 (H2 + Flyway persistence)
--         DEC-17 (eager schema; tenant_id NOT NULL; no seed data)
-- ============================================================
--
-- This migration creates the `set_result` table — the smallest mutation unit
-- of the scoring system. Each row represents one set within one match.
--
-- Design decisions:
--   - Composite PRIMARY KEY (match_id, set_index) per D-36 and AC3.
--     This natural key enables the D-36 SELECT-before-INSERT/UPDATE correction
--     detection in the cascade service (E03S11): if a row exists at
--     (match_id, set_index) it is a correction; if absent it is a first entry.
--   - set_state stored as INT with CHECK constraint (legacy enum codes per O-9).
--   - phase_id is denormalized (authoritative source is match.phase_id) but kept
--     for query performance per the story notes (cascade step 10 "all open sets
--     in phase X" query).
--   - change_time is NOT a replacement for audit_log (added in E03S04); it is
--     a simple "last modified" cache from the legacy MatchResult.
--   - STANDOFF(3) is structurally unreachable in V1 (no SetValidationRule allows
--     set-level ties), but kept in the enum for legacy parity.
--
-- No INSERT statements per DEC-17 / AC8.
-- All FK references use ON DELETE RESTRICT to preserve data integrity.
-- ============================================================

-- ------------------------------------------------------------
-- set_result
-- AC2 — columns (all NOT NULL)
-- AC3 — composite primary key (match_id, set_index)
-- AC4 — CHECK constraint on set_state
-- AC5 — CHECK constraints on team1_points and team2_points (>= 0)
-- ------------------------------------------------------------
CREATE TABLE set_result (
    match_id            UUID          NOT NULL,
    set_index           INT           NOT NULL,
    tenant_id           UUID          NOT NULL,
    phase_id            UUID          NOT NULL,
    team1_points        INT           NOT NULL,
    team2_points        INT           NOT NULL,
    set_state           INT           NOT NULL,
    -- change_time: last-modification cache (legacy parity; NOT a substitute for audit_log)
    change_time         TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    created_at          TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    -- AC3: composite primary key per D-36
    CONSTRAINT pk_set_result PRIMARY KEY (match_id, set_index),

    -- Tenant scope per DEC-5 / DEC-17
    CONSTRAINT fk_set_result_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
        ON DELETE RESTRICT,

    -- Match membership — every set belongs to exactly one match
    CONSTRAINT fk_set_result_match
        FOREIGN KEY (match_id) REFERENCES match (id)
        ON DELETE RESTRICT,

    -- Phase membership — denormalized for query performance (AC2 / story notes)
    CONSTRAINT fk_set_result_phase
        FOREIGN KEY (phase_id) REFERENCES phase (id)
        ON DELETE RESTRICT,

    -- AC4: legacy set state enum codes only.
    -- OPEN(0), WINNER1(1), WINNER2(2), STANDOFF(3), CANCELED(-1)
    -- STANDOFF(3) is structurally unreachable in V1 but kept for legacy parity.
    CONSTRAINT chk_set_result_state
        CHECK (set_state IN (0, 1, 2, 3, -1)),

    -- AC5: scores are non-negative — negative scores are physically impossible;
    -- the CHECK catches bugs in the application layer.
    CONSTRAINT chk_set_result_team1_points
        CHECK (team1_points >= 0),
    CONSTRAINT chk_set_result_team2_points
        CHECK (team2_points >= 0)
);

-- AC6: index on (phase_id, set_state) — supports "all open sets in phase X"
-- query used by the cascade service's auto-lap-advance step 10 (D-21).
CREATE INDEX idx_set_result_phase_state ON set_result (phase_id, set_state);

-- Tenant-scoped filter per D-34 / DEC-17
CREATE INDEX idx_set_result_tenant_id ON set_result (tenant_id);
