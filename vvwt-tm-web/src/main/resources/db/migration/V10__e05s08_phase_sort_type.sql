-- ============================================================
-- V10__e05s08_phase_sort_type.sql — Persist sort type + group count on Phase entity
-- Story:  E05S08
-- DECs:   DEC-9  (TeamAvatar structural identity)
--         DEC-14 (H2 + Flyway persistence — additive migration)
--         DEC-17 (eager schema; default value for existing rows)
-- ============================================================
--
-- The mapping suggestion endpoint (AC1) needs to know:
--   1. The sort type for the target Phase 2+ (determines redistribution algorithm)
--   2. The group count for the target phase (determines how many target groups exist)
--
-- Both were previously only present in the draft JSON, which is cleared after apply (E05S06).
-- This migration persists both on the phase row so the mapping service can retrieve them.
--
-- sort_type valid values (from DraftSection.validate()):
--   'team_number'     — sort by team number (fallback / Phase 1 default)
--   'placement_group' — sort by placement within source group, then group number
--   'group_placement' — sort by group number, then placement within group
--
-- group_count: number of groups configured for this phase (≥ 1).
--
-- Defaults for existing rows:
--   sort_type  DEFAULT 'team_number' — correct for Phase 1 (team_number is always used for Phase 1)
--   group_count DEFAULT 1            — safe placeholder; Phase 1 group count is derived from
--                                      existing TeamAvatars. Phase 2+ created after this migration
--                                      will have the correct value set by DraftService.
-- ============================================================

ALTER TABLE phase
    ADD COLUMN IF NOT EXISTS sort_type  VARCHAR NOT NULL DEFAULT 'team_number';

ALTER TABLE phase
    ADD COLUMN IF NOT EXISTS group_count INT     NOT NULL DEFAULT 1;
