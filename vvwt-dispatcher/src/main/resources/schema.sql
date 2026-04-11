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

-- ============================================================================
-- Identity registry schema (E01S06 AC1–AC4)
-- ============================================================================
-- Table: registered_keys
--
-- Holds all registered Ed25519 public keys (workers and submitters).
-- A key that has been superseded keeps its row for audit; it is accepted
-- during the grace window (grace_expires_at) and hard-rejected after.
-- ============================================================================

CREATE TABLE IF NOT EXISTS registered_keys (
    key_id                   UUID             NOT NULL,
    role                     VARCHAR(16)      NOT NULL,
    public_key_bytes         BYTEA            NOT NULL,
    registered_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    superseded_at            TIMESTAMP WITH TIME ZONE,
    grace_expires_at         TIMESTAMP WITH TIME ZONE,
    superseded_by_key_id     UUID,
    name                     VARCHAR(255),
    PRIMARY KEY (key_id)
);

-- ============================================================================
-- Job intake schema (E01S06 AC5, AC10)
-- ============================================================================
-- Table: jobs
--
-- One row per accepted submit-job call (cache-miss path only).
-- Cache-hit submissions do NOT create a job row.
-- Status values: queued | decomposing | ready | done | failed
-- ============================================================================

CREATE TABLE IF NOT EXISTS jobs (
    job_id                   UUID             NOT NULL,
    submitter_key_id         UUID             NOT NULL,
    phase_id                 INTEGER          NOT NULL,
    raw_phase_def_json       TEXT             NOT NULL,
    canonical_phase_def_json TEXT             NOT NULL,
    fingerprint              BYTEA            NOT NULL,
    status                   VARCHAR(16)      NOT NULL,
    submitted_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    packet_count             INTEGER,
    priority                 INTEGER          NOT NULL DEFAULT 0,
    PRIMARY KEY (job_id)
);

-- ============================================================================
-- Audit log schema (E01S06 AC12)
-- ============================================================================
-- Table: audit_log
--
-- Append-only. Every register-key, submit-job, and pull-packet call creates one row,
-- regardless of outcome. Never updated or deleted by the dispatcher.
-- ============================================================================

CREATE TABLE IF NOT EXISTS audit_log (
    audit_id                 BIGINT           NOT NULL GENERATED ALWAYS AS IDENTITY,
    logged_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    source_ip                VARCHAR(64)      NOT NULL,
    endpoint                 VARCHAR(32)      NOT NULL,
    key_id                   UUID,
    signature_outcome        VARCHAR(16),
    http_status              INTEGER          NOT NULL,
    PRIMARY KEY (audit_id)
);

-- ============================================================================
-- Packet decomposition & distribution schema (E01S07 AC1–AC8)
-- ============================================================================
-- Table: packets
--
-- One row per rank-interval packet created by the decomposer (AC1).
-- status values: pending | assigned | done | failed
-- reissue_history — TEXT column holding a JSON array; each entry records
--   { "timestamp": "...", "workerKeyId": "...", "attemptNumber": N }
--   appended by the timeout sweeper on each reissue (AC8).
-- ============================================================================

CREATE TABLE IF NOT EXISTS packets (
    packet_id                UUID             NOT NULL,
    job_id                   UUID             NOT NULL REFERENCES jobs(job_id),
    rank_from                BIGINT           NOT NULL,
    rank_to                  BIGINT           NOT NULL,
    status                   VARCHAR(16)      NOT NULL,
    attempts                 INTEGER          NOT NULL DEFAULT 0,
    assigned_to              UUID,
    assigned_at              TIMESTAMP WITH TIME ZONE,
    deadline                 TIMESTAMP WITH TIME ZONE,
    reissue_history          TEXT,
    -- First-valid-wins result fields (E01S08 AC3): set when packet transitions to done
    first_best_rank          BIGINT,
    first_best_score         DOUBLE PRECISION,
    first_worker_key_id      UUID,
    PRIMARY KEY (packet_id)
);

-- Fast lookup: pending packets for a job (used by pull-packet) (AC5, AC7)
CREATE INDEX IF NOT EXISTS idx_packets_job_status
    ON packets(job_id, status);

-- Fast sweep: assigned packets older than timeout (AC8)
-- Note: a partial index (WHERE status = 'assigned') would be ideal in PostgreSQL for performance
-- but is omitted here for H2 compatibility (test DB). The query optimizer uses assigned_at ordering.
CREATE INDEX IF NOT EXISTS idx_packets_assigned_at
    ON packets(assigned_at);

-- ============================================================================
-- Late results log schema (E01S08 AC4)
-- ============================================================================
-- Table: late_results
--
-- Receives every result submission for a packet that is already done.
-- matchesFirst: true if (bestRank, bestScore) match the first accepted result.
-- A WARN log is emitted when matchesFirst=false (AC5).
-- ============================================================================

CREATE TABLE IF NOT EXISTS late_results (
    late_result_id           BIGINT           NOT NULL GENERATED ALWAYS AS IDENTITY,
    packet_id                UUID             NOT NULL,
    job_id                   UUID             NOT NULL,
    worker_key_id            UUID             NOT NULL,
    best_rank                BIGINT           NOT NULL,
    best_score               DOUBLE PRECISION NOT NULL,
    received_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    matches_first            BOOLEAN          NOT NULL,
    PRIMARY KEY (late_result_id)
);

-- ============================================================================
-- Result intake audit log schema (E01S08 AC8)
-- ============================================================================
-- Table: result_audit_log
--
-- Append-only. Every submit-result call creates one row, regardless of outcome.
-- decisionOutcome: ACCEPTED_FIRST | ACCEPTED_LATE | REJECTED_NOT_ASSIGNED |
--                  REJECTED_DEADLINE | REJECTED_SIGNATURE | REJECTED_OTHER
-- ============================================================================

CREATE TABLE IF NOT EXISTS result_audit_log (
    audit_id                 BIGINT           NOT NULL GENERATED ALWAYS AS IDENTITY,
    logged_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    source_ip                VARCHAR(64)      NOT NULL,
    worker_key_id            UUID,
    packet_id                UUID,
    job_id                   UUID,
    signature_outcome        VARCHAR(16),
    decision_outcome         VARCHAR(32),
    http_status              INTEGER          NOT NULL,
    PRIMARY KEY (audit_id)
);
