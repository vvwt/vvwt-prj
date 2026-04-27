-- V1__initial_schema.sql (H2 dialect)
-- Story: E38S03 — DEC-42 D4, DEC-43 D4, DEC-46
-- 5-table schema for vvwt-info-server (self-host / H2 profile).
-- H2 dialect notes:
--   * public_key: VARBINARY(8192) — 8 KB cap covers Ed25519 (32B), ML-DSA-65 (1952B),
--     SLH-DSA-128f (32B) with 3x headroom (AC8).
--   * tournament (tenant_id, location_id) active-tournament uniqueness: H2 does not
--     support partial unique indexes (WHERE clause). Enforced at application layer
--     (service-layer guard at supersede time per AC12). See AC12 notes.
--   * state: CLOB — tournament.state is plain text per DEC-42 D4 + Brief T-6.

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
-- DEC-42 D3: is_default distinguishes default-tenant from non-default (C-B1a/C-B1b).
-- DEC-43 D4: public_key may be Ed25519 (32B), ML-DSA-65 (1952B), SLH-DSA (32B);
--             VARBINARY(8192) provides 3x headroom for all known PQC sizes (AC8).
-- ----------------------------------------------------------------
CREATE TABLE tenant (
    tenant_id      VARCHAR(128)  NOT NULL,
    public_key     VARBINARY(8192) NOT NULL,
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
-- DEC-42 D4, Brief D-13:
--   * tournament_token: opaque bearer token distributed via QR code.
--   * per_tournament_secret: 32-byte HMAC secret (D-X3 c1) — NEVER returned to clients
--     except to TM at tournament-registration response (AC4, AC Security notes).
--   * state: plain TEXT/CLOB blob per DEC-42 D4 + Brief T-6 (no jsonb Phase 1).
--   * last_applied_seq: tracks latest applied delta sequence number.
--   * superseded_at NULL → active tournament; non-NULL → superseded (24h grace window per E38S06).
-- Composite uniqueness (tenant_id, location_id) WHERE superseded_at IS NULL:
--   H2 limitation: no partial unique indexes. Enforced at service layer (AC12).
-- ----------------------------------------------------------------
CREATE TABLE tournament (
    tournament_id      VARCHAR(128)  NOT NULL,
    tenant_id          VARCHAR(128)  NOT NULL,
    location_id        VARCHAR(128)  NOT NULL,
    tournament_token   VARCHAR(256)  NOT NULL,
    per_tournament_secret VARBINARY(32) NOT NULL,
    state              CLOB          NULL,
    last_applied_seq   BIGINT        NOT NULL DEFAULT 0,
    registered_at      TIMESTAMP     NOT NULL,
    superseded_at      TIMESTAMP     NULL,
    CONSTRAINT pk_tournament PRIMARY KEY (tournament_id),
    CONSTRAINT uq_tournament_token UNIQUE (tournament_token),
    CONSTRAINT fk_tournament_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant (tenant_id)
);

-- ----------------------------------------------------------------
-- tournament_delta — event log per tournament (AC13)
-- Composite PK (tournament_id, seq) enforces uniqueness of sequence within tournament.
-- Gap-free monotonicity of seq is enforced at service layer (E38S05).
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
-- signature_outcome: VALID / INVALID / NA  (D-X5 split)
-- rejection_reason: KEY_MISMATCH / ALGORITHM_DEPRECATED / ... / null (when accepted)
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
