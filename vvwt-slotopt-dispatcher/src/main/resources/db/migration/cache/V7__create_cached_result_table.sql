-- Flyway migration V7 — cache.cached_result table
-- Story: E37S10
-- DEC-26 (analogously applied): this is the single schema source of truth for CachedResultRepositoryIT
-- (DEC-26 Rule 1: schema-from-migration).
-- DEC-9: cache keys are structural (structural_fingerprint + game_mode), no UUIDs cross the boundary.
-- Composite primary key (structural_fingerprint, game_mode) — enforced by @WritingConverter/@ReadingConverter pair.
-- first_accepted_job_id: FK/reference for traceability to the job whose accepted result populated this cache entry.

CREATE TABLE cached_result (
    structural_fingerprint  VARBINARY(32)            NOT NULL,
    game_mode               VARCHAR(64)              NOT NULL,
    result_payload_json     VARCHAR(65536)           NOT NULL,
    cached_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    first_accepted_job_id   UUID                     NOT NULL,
    PRIMARY KEY (structural_fingerprint, game_mode)
);
