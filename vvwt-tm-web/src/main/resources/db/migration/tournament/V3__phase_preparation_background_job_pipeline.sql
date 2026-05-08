-- ============================================================
-- tournament/V3__phase_preparation_background_job_pipeline.sql
-- Story: E51S01 (Foundation — DEC-55 authoring + Flyway schema migration)
-- DECs:  DEC-55 D-2 (schema migration specification)
--        DEC-25 (Wave-2 Big-Bang-Reset: no production data — schema-only migration)
--        DEC-9  (TeamAvatar structural identity via (phaseId, groupNumber, groupPosition);
--                teamId nullable enables structural-placeholder avatars at DraftConfig-Apply)
-- ============================================================
--
-- Schema deltas per DEC-55 D-2:
--   1. team_avatar.team_id:       NOT NULL → NULL (structural-placeholder avatars)
--   2. tournament.optimize:       NEW BOOLEAN NOT NULL DEFAULT TRUE (operator slot-opt switch)
--   3. phase.optimized:           NEW BOOLEAN NOT NULL DEFAULT FALSE (slot-opt completion flag)
--   4. phase.last_job_state:      NEW VARCHAR NULL (background-job state audit + restart-recovery)
--   5. phase.status:              VARCHAR already accepts any value; ASSIGNED enum value usage
--                                 governed at application layer (no DB CHECK constraint added)
--   6. match.fk_member_avatar_1:  ON DELETE RESTRICT → ON DELETE CASCADE (auto-invalidation)
--   7. match.fk_member_avatar_2:  ON DELETE RESTRICT → ON DELETE CASCADE (auto-invalidation)
--   8. team_avatar_rating.fk:     ON DELETE RESTRICT → ON DELETE CASCADE (auto-invalidation)
--
-- DEC-25 §Wave-2-Big-Bang-Reset: no UPDATE statements; no data backfill.
-- ============================================================


-- ============================================================
-- 1. team_avatar.team_id: make nullable (structural-placeholder avatars per DEC-55 D-1 + D-2)
--
--    DEC-9 structural identity (phaseId, groupNumber, groupPosition) is preserved; teamId
--    is NOT part of the structural-identity UNIQUE constraint (uq_team_avatar_structural_identity).
--    The FK fk_team_avatar_team remains in place — a non-NULL team_id still references team(id).
--    For structural-placeholder rows (teamId NULL), no FK check fires (SQL NULL semantics).
-- ============================================================
ALTER TABLE team_avatar ALTER COLUMN team_id UUID NULL;


-- ============================================================
-- 2. tournament.optimize: operator-controlled slot-opt switch (DEC-55 D-5)
--
--    Default TRUE: slot-optimization is computed for all tournaments by default.
--    Operator may set FALSE at Tournament-create / DraftConfig-edit via TournamentForm.svelte
--    checkbox (DEC-55 D-5, UI delivered by E51S07).
-- ============================================================
ALTER TABLE tournament ADD COLUMN optimize BOOLEAN NOT NULL DEFAULT TRUE;


-- ============================================================
-- 3. phase.optimized: slot-opt completion flag (DEC-55 D-6)
--
--    Default FALSE: not yet optimized at phase creation.
--    Flipped to TRUE by SlotOptJobCompletedEvent listener (E51S04) or by operator-cancel
--    Best-So-Far path (DEC-49 D-11a).
--    Activation-guard: phase ASSIGNED → ACTIVE requires (!tournament.optimize OR phase.optimized).
-- ============================================================
ALTER TABLE phase ADD COLUMN optimized BOOLEAN NOT NULL DEFAULT FALSE;


-- ============================================================
-- 4. phase.last_job_state: background-job state audit + restart-recovery (DEC-55 D-2 + D-8)
--
--    Nullable: NULL means no background job has run for this phase yet.
--    Permitted values (enforced at application layer, not DB CHECK):
--      'match_gen_running' | 'slot_opt_running' | 'idle' | 'cancelled' | 'failed'
--    Used by JobQueueRecoveryService (E51S07) to reconcile in-flight jobs on JVM restart.
-- ============================================================
ALTER TABLE phase ADD COLUMN last_job_state VARCHAR NULL;


-- ============================================================
-- 6-7. match FK cascade: ON DELETE RESTRICT → ON DELETE CASCADE for avatar FKs (DEC-55 D-7)
--
--    Phase-precise auto-invalidation cascade: when team_avatar rows are deleted (phase-shape
--    change invalidates avatars), dependent match rows are auto-deleted via CASCADE.
--    fk_team_avatar_team (team_avatar → team) remains ON DELETE RESTRICT — intentional.
-- ============================================================
ALTER TABLE match DROP CONSTRAINT fk_match_member_avatar_1;
ALTER TABLE match ADD CONSTRAINT fk_match_member_avatar_1
    FOREIGN KEY (member_avatar_1_id) REFERENCES team_avatar (id)
    ON DELETE CASCADE;

ALTER TABLE match DROP CONSTRAINT fk_match_member_avatar_2;
ALTER TABLE match ADD CONSTRAINT fk_match_member_avatar_2
    FOREIGN KEY (member_avatar_2_id) REFERENCES team_avatar (id)
    ON DELETE CASCADE;


-- ============================================================
-- 8. team_avatar_rating FK cascade: ON DELETE RESTRICT → ON DELETE CASCADE (DEC-55 D-7)
--
--    Rating rows are invalidated when their avatar is deleted during phase-shape changes.
-- ============================================================
ALTER TABLE team_avatar_rating DROP CONSTRAINT fk_team_avatar_rating_avatar;
ALTER TABLE team_avatar_rating ADD CONSTRAINT fk_team_avatar_rating_avatar
    FOREIGN KEY (avatar_id) REFERENCES team_avatar (id)
    ON DELETE CASCADE;
