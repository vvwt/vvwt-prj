-- ============================================================================
-- Results cache schema (E01S09 AC6)
-- ============================================================================
-- Table: cached_results
--
-- Composite PK: (fingerprint, score_fn_version, canonicalization_version)
--   fingerprint              — 32-byte SHA-256 of the CanonicalPhaseDef
--   score_fn_version         — invalidates on scorer algorithm changes
--   canonicalization_version — invalidates if the 5-step fingerprint rule changes
--
-- No eviction: results are deterministic and reusable across dispatcher versions.
--
-- Security (AC10):
--   The write role is the local dispatcher DB user (connection credential held
--   only by the dispatcher process — not exposed externally).
--   A separate read-only role 'cache_reader' is granted SELECT-only privilege.
--   The read-public REST endpoint (GET /cache/…) runs in the dispatcher process
--   and uses the cache_reader role via a separate DataSource / connection pool.
--   DDL for the role grant:
--     CREATE ROLE cache_reader;
--     GRANT SELECT ON cached_results TO cache_reader;
-- ============================================================================

CREATE TABLE IF NOT EXISTS cached_results (
    fingerprint              BYTEA            NOT NULL,
    score_fn_version         INTEGER          NOT NULL,
    canonicalization_version INTEGER          NOT NULL,
    best_rank                BIGINT           NOT NULL,
    best_score               DOUBLE PRECISION NOT NULL,
    n                        INTEGER          NOT NULL,
    computed_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    source_job_id            UUID             NOT NULL,
    PRIMARY KEY (fingerprint, score_fn_version, canonicalization_version)
);
