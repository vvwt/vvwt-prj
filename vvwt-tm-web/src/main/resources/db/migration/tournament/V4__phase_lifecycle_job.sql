-- ============================================================
-- tournament/V4__phase_lifecycle_job.sql
-- Story: E55S02 (Job-queue schema migration + DAO + CAS claim + DAO IT)
-- DECs:  DEC-64 D-4 (DB-durable per-tenant job queue schema — verbatim)
--        DEC-25 (Wave-2 Big-Bang-Reset: no production data — schema-only migration)
--        DEC-22 (TDD Iron Law: schema matches GREEN test target)
-- ============================================================
--
-- Creates the per-tenant phase_lifecycle_job table.
-- This table is the DB-durable replacement for the DEC-55 D-3a in-memory FIFO queue
-- (DEC-49 T-6 in-memory tracking trade-off eliminated per DEC-64 D-4 + D-6).
--
-- Schema per DEC-64 D-4 verbatim:
--   id            UUID PRIMARY KEY
--   tournament_id UUID NOT NULL
--   phase_id      UUID NOT NULL
--   game_mode     VARCHAR NOT NULL         -- carries DraftSection.gameMode
--   sequence      INT NOT NULL             -- phase.sequenceNumber for FIFO ordering
--   status        VARCHAR NOT NULL         -- 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
--   cancelled     BOOLEAN NOT NULL DEFAULT FALSE
--   claimed_by    VARCHAR(64)              -- JVM-instance identifier; NULL when status='PENDING'
--   claimed_at    TIMESTAMP
--   completed_at  TIMESTAMP
--   enqueued_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
--   FK phase_id → phase(id) ON DELETE CASCADE
--
-- DEC-25 §Wave-2-Big-Bang-Reset: no UPDATE statements; no data backfill.
-- ============================================================

CREATE TABLE phase_lifecycle_job (
    id            UUID          NOT NULL,
    tournament_id UUID          NOT NULL,
    phase_id      UUID          NOT NULL,
    game_mode     VARCHAR       NOT NULL,
    sequence      INT           NOT NULL,
    status        VARCHAR       NOT NULL,
    cancelled     BOOLEAN       NOT NULL  DEFAULT FALSE,
    claimed_by    VARCHAR(64),
    claimed_at    TIMESTAMP,
    completed_at  TIMESTAMP,
    enqueued_at   TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_phase_lifecycle_job PRIMARY KEY (id),
    CONSTRAINT fk_phase_lifecycle_job_phase FOREIGN KEY (phase_id)
        REFERENCES phase (id) ON DELETE CASCADE
);

-- Regular index on (tournament_id, sequence) — partial-index H2 adaptation.
--
-- DEC-64 D-4 schema specifies a partial index (WHERE status='PENDING'). H2 2.3.232
-- does NOT support the WHERE clause on CREATE INDEX (syntax error 42000). Since this
-- project uses H2 as the only V1 persistence layer (DEC-14), the partial index is
-- replaced with a regular index on the same columns. The query-planner benefit is
-- retained for the CAS SELECT; the additional space overhead on non-PENDING rows is
-- negligible at V1 scale.
--
-- Named identically to the DEC-64 D-4 index name for grep-consistency with the DEC.
CREATE INDEX idx_phase_lifecycle_job_tournament_pending
    ON phase_lifecycle_job (tournament_id, sequence);
