-- ============================================================
-- V14__e08s05_draft_config.sql — Draft configuration column
-- Story:  E08S05 (ported from E05S06 — V8 was taken by e06s03)
-- DECs:   DEC-5  (PLANNED is not ACTIVE — active_sentinel stays NULL)
--         DEC-14 (H2 + Flyway persistence — additive migration)
-- ============================================================
--
-- Adds the draft_json column to the tournament table to store the organizer's
-- draft configuration as a JSON text blob (E05S06 AC2, AC3 — backfilled here
-- because V8 slot was taken by e06s03 in the staging branch divergence).
--
-- The draft is a transient planning object persisted as a JSON TEXT column
-- on the Tournament entity. This keeps the schema simple and avoids a new
-- entity lifecycle for a purely planning artefact.
--
-- The PLANNED status (AC7) is an intermediate state between DRAFT and ACTIVE:
--   DRAFT   — tournament created; teams registered; draft being configured
--   PLANNED — draft applied; phases created; but no phase is active yet
--   ACTIVE  — first phase started
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
