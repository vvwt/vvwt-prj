-- ============================================================
-- V9__e05s07_phase_lifecycle.sql — Phase lifecycle audit log
-- Story:  E05S07
-- DECs:   DEC-5  (tenant scope on all rows)
--         DEC-14 (H2 + Flyway persistence — additive migration)
--         DEC-17 (eager schema; tenant_id NOT NULL)
-- ============================================================
--
-- The existing audit_log table (V5) records set-level result corrections with a
-- NOT NULL match_id FK. Phase-level governance actions (e.g. forced lap advance per AC5)
-- do not belong to any single match — they are phase-level events. To preserve the
-- append-only invariant and avoid relaxing the match_id NOT NULL constraint in V5,
-- a separate table is introduced here.
--
-- phase_audit_log:
--   Records governance actions taken on a Phase (forced lap advances, etc.).
--   Append-only by repository-layer convention — no UPDATE/DELETE operations permitted.
-- ============================================================

CREATE TABLE phase_audit_log (
    id                      UUID          NOT NULL,
    tenant_id               UUID          NOT NULL,
    phase_id                UUID          NOT NULL,

    -- action: enum-like string identifying the governance action taken.
    -- 'FORCE_ADVANCE_LAP' is the only action in V1 (AC5 — E05S07).
    action                  VARCHAR       NOT NULL,

    -- lap_number: the lap number at the time of the action.
    -- For FORCE_ADVANCE_LAP: the lap being forced past.
    lap_number              INT           NOT NULL,

    -- unfinished_match_count: number of unfinished matches in the current lap
    -- at the time the forced advance was confirmed (AC5).
    unfinished_match_count  INT           NOT NULL,

    -- actor_id: identity of the organiser who performed the action.
    -- NULL in default-tenant LAN mode where no login is required (DEC-5).
    actor_id                VARCHAR       NULL,

    changed_at              TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_phase_audit_log PRIMARY KEY (id),

    -- Tenant scope per DEC-5 / DEC-17
    CONSTRAINT fk_phase_audit_log_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
        ON DELETE RESTRICT,

    -- Phase FK — every phase audit entry belongs to exactly one phase
    CONSTRAINT fk_phase_audit_log_phase
        FOREIGN KEY (phase_id) REFERENCES phase (id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_phase_audit_log_unfinished_nonneg
        CHECK (unfinished_match_count >= 0)
);

-- Support "show me the governance history for this phase" query (AC5).
CREATE INDEX idx_phase_audit_log_phase_id ON phase_audit_log (phase_id, changed_at);

-- Tenant-scoped filter per DEC-17.
CREATE INDEX idx_phase_audit_log_tenant_id ON phase_audit_log (tenant_id);
