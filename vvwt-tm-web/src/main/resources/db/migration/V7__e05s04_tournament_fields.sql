-- ============================================================
-- V7__e05s04_tournament_fields.sql — Tournament CRUD fields
-- Story:  E05S04
-- DECs:   DEC-5  (single-active-tournament; existing active_sentinel unchanged)
--         DEC-14 (H2 + Flyway persistence)
--         DEC-17 (eager schema)
-- ============================================================
--
-- Adds three columns to the tournament table to support the full
-- Tournament CRUD REST API (AC1, AC3, AC8):
--
--   appointment   TIMESTAMP  — optional tournament date; NULL until set by organizer
--   field_count   INT        — court count (AC3: ≥1, validated at application level)
--   team_count    INT        — team count  (AC3: ≥2, validated at application level)
--
-- Default values allow existing rows (from test data and prior migrations) to stay
-- valid without a data backfill. field_count=1 and team_count=2 are the smallest
-- valid values per the story acceptance criteria.
-- ============================================================

ALTER TABLE tournament
    ADD COLUMN IF NOT EXISTS appointment   TIMESTAMP    DEFAULT NULL;

ALTER TABLE tournament
    ADD COLUMN IF NOT EXISTS field_count   INT          NOT NULL DEFAULT 1;

ALTER TABLE tournament
    ADD COLUMN IF NOT EXISTS team_count    INT          NOT NULL DEFAULT 2;
