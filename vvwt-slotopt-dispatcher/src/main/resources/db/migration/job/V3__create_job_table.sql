-- Flyway migration V3 — job.job table
-- Story: E37S07
-- DEC-26 (analogously applied): this is the single schema source of truth for the
-- JobRepositoryIT (DEC-26 Rule 1: schema-from-migration).
-- Schema per AC-JOB-RECORD-ENTITY: job tracking for the dispatcher submit-job endpoint.
-- status values: RECEIVED, DECOMPOSED, COMPLETED (per spec section (b)).

CREATE TABLE job (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id          UUID                     NOT NULL UNIQUE,
    submitted_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    job_def_json    VARCHAR(65536)           NOT NULL,
    status          VARCHAR(32)              NOT NULL
);
