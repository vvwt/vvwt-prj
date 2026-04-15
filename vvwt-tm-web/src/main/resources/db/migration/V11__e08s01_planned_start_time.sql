-- ============================================================
-- V11__e08s01_planned_start_time.sql — E08S01 AC1: planned start time on tournament
-- Story:  E08S01
-- DECs:   DEC-5  (multi-tenant invariants)
--         DEC-14 (H2 + Flyway persistence)
--         DEC-17 (eager schema; nullable on existing table is safe)
-- ============================================================
--
-- Adds a nullable TIME column to the tournament table to record the organizer's
-- planned tournament start time (time-of-day, no date component).
-- Nullable because existing tournaments and tournaments not using print output
-- do not require a start time.
--
-- AC1: planned_start_time TIME NULL on tournament table.
-- AC9: migration is additive (no data backfill); existing rows receive NULL automatically.
-- ============================================================

ALTER TABLE tournament ADD COLUMN planned_start_time TIME NULL;
