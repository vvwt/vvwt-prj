-- V1__initial_schema.sql (PostgreSQL dialect)
-- Story: E38S03 — DEC-42 D4, DEC-43 D4, DEC-46
-- 5-table schema for vvwt-info-server (primary / PostgreSQL profile).
-- PostgreSQL dialect notes:
--   * public_key: BYTEA (length-unbounded) — accommodates Ed25519 (32B), ML-DSA-65 (1952B),
--     SLH-DSA (32B). No length limit in PostgreSQL BYTEA syntax (AC8).
--     Application-layer CHECK enforces octet_length <= 8192 (matches H2 VARBINARY(8192) cap).
--   * per_tournament_secret: BYTEA NOT NULL (32 bytes, enforced at service layer).
--   * state: TEXT — tournament.state is plain text per DEC-42 D4 + Brief T-6 (no jsonb Phase 1).
--   * Partial unique index on tournament(tenant_id, location_id) WHERE superseded_at IS NULL
--     enforces C-B1c (one active tournament per (tenant_id, location_id)) at DB level (AC12).

-- ----------------------------------------------------------------
-- algorithm_registry — announces supported signature algorithms
-- DEC-43 D1, D4: algorithm_id is server-canonical string identifier.
-- ----------------------------------------------------------------
CREATE TABLE algorithm_registry (
    algorithm_id     VARCHAR(64)  NOT NULL,
    display_name     VARCHAR(128) NOT NULL,
    deprecation_date DATE         NULL,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    parameters       TEXT         NULL,     -- JSON-encoded UTF-8 when non-null; opaque to DB layer
    CONSTRAINT pk_algorithm_registry PRIMARY KEY (algorithm_id)
);

-- ----------------------------------------------------------------
-- tenant — registered publishers (TM instances)
-- DEC-43 D4: BYTEA length-unbounded for PQC future-readiness (AC8).
-- Application-layer guard enforces octet_length(public_key) <= 8192.
-- ----------------------------------------------------------------
CREATE TABLE tenant (
    tenant_id      VARCHAR(128) NOT NULL,
    public_key     BYTEA        NOT NULL,
    algorithm_id   VARCHAR(64)  NOT NULL,
    registered_at  TIMESTAMP    NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    is_default     BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_tenant PRIMARY KEY (tenant_id),
    CONSTRAINT fk_tenant_algorithm FOREIGN KEY (algorithm_id)
        REFERENCES algorithm_registry (algorithm_id)
);

-- ----------------------------------------------------------------
-- tournament — registered tournaments (per tenant+location)
-- ----------------------------------------------------------------
CREATE TABLE tournament (
    tournament_id      VARCHAR(128)  NOT NULL,
    tenant_id          VARCHAR(128)  NOT NULL,
    location_id        VARCHAR(128)  NOT NULL,
    tournament_token   VARCHAR(256)  NOT NULL,
    per_tournament_secret BYTEA      NOT NULL,
    state              TEXT          NULL,
    last_applied_seq   BIGINT        NOT NULL DEFAULT 0,
    registered_at      TIMESTAMP     NOT NULL,
    superseded_at      TIMESTAMP     NULL,
    CONSTRAINT pk_tournament PRIMARY KEY (tournament_id),
    CONSTRAINT uq_tournament_token UNIQUE (tournament_token),
    CONSTRAINT fk_tournament_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
);

-- Partial unique index: one active tournament per (tenant_id, location_id) — AC12.
-- C-B1c: superseded_at IS NULL means tournament is active. PostgreSQL partial index syntax.
CREATE UNIQUE INDEX uq_active_tournament_per_location
    ON tournament (tenant_id, location_id)
    WHERE superseded_at IS NULL;

-- ----------------------------------------------------------------
-- tournament_delta — event log per tournament (AC13)
-- ----------------------------------------------------------------
CREATE TABLE tournament_delta (
    tournament_id  VARCHAR(128)  NOT NULL,
    seq            BIGINT        NOT NULL,
    event_type     VARCHAR(64)   NOT NULL,
    event_payload  TEXT          NOT NULL,
    applied_at     TIMESTAMP     NOT NULL,
    CONSTRAINT pk_tournament_delta PRIMARY KEY (tournament_id, seq),
    CONSTRAINT fk_delta_tournament FOREIGN KEY (tournament_id)
        REFERENCES tournament (tournament_id)
);

-- ----------------------------------------------------------------
-- audit_log — append-only request audit trail (AC4, AC10)
-- ----------------------------------------------------------------
CREATE TABLE audit_log (
    id                BIGINT GENERATED ALWAYS AS IDENTITY,
    request_id        VARCHAR(128)  NULL,
    source_ip         VARCHAR(64)   NOT NULL,
    timestamp_utc     TIMESTAMP     NOT NULL,
    signature_outcome VARCHAR(32)   NULL,
    rejection_reason  VARCHAR(64)   NULL,
    http_status       INT           NOT NULL,
    tenant_id         VARCHAR(128)  NULL,
    tournament_id     VARCHAR(128)  NULL,
    request_path      VARCHAR(512)  NOT NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (id)
);
