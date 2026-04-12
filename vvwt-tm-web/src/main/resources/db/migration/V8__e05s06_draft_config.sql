-- ============================================================
-- V8__e05s06_draft_config.sql — Draft configuration column
-- Story:  E05S06
-- DECs:   DEC-5  (PLANNED is not ACTIVE — active_sentinel stays NULL)
--         DEC-14 (H2 + Flyway persistence — additive migration)
-- ============================================================
--
-- Adds the draft_json column to the tournament table to store the organizer's
-- draft configuration as a JSON text blob (AC2, AC3 — E05S06).
--
-- The draft is a transient planning object persisted as a JSON TEXT column
-- on the Tournament entity (per AC2 delivery decision: store on tournament,
-- not as a separate entity). This keeps the schema simple and avoids a new
-- entity lifecycle for a purely planning artefact.
--
-- The PLANNED status (AC7) is an intermediate state between DRAFT and ACTIVE:
--   DRAFT   — tournament created; teams registered; draft being configured
--   PLANNED — draft applied; phases created; but no phase is active yet
--   ACTIVE  — first phase started (E05S07)
--   COMPLETED / CANCELLED — existing terminal states
--
-- DEC-5 active_sentinel: the generated expression only fires for 'ACTIVE'.
-- PLANNED tournaments have active_sentinel = NULL, so the unique index does
-- not restrict PLANNED count per tenant.
-- ============================================================

ALTER TABLE tournament
    ADD COLUMN IF NOT EXISTS draft_json TEXT DEFAULT NULL;

-- Note: no migration data needed — existing DRAFT rows have draft_json = NULL,
-- meaning "no draft configured yet" (returns empty sections array per AC3).
