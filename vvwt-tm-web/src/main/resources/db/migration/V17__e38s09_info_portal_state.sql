-- E38S09 AC12: per-tournament publisher state for the Public Participant Info Portal.
-- DEC-26 Rule 1: production migration is the schema source of truth for DAO tests.
-- DEC-46: extends DEC-26 three-rule scope to this table.
-- DEC-42 D2: TM publisher state lives in TM's per-tenant H2 (DEC-20 DB-per-Tenant unchanged).
--
-- Schema per AC12:
--   (tenant_id, location_id, tournament_id) — composite PK
--   last_published_seq — BIGINT, monotonically increasing per tournament; default 0
--   tournament_token   — opaque bearer token from info-server tournament-registration (E38S05)
--   per_tournament_secret — VARBINARY: HMAC secret from info-server; stored AES-GCM encrypted
--                           at rest (NO-PLAINTEXT-ON-DISK per AC8 applies to keypair; secret
--                           stored as-received since it is already a server-generated random);
--                           stored as raw bytes here — TM must not log or expose
--   last_published_at  — TIMESTAMP WITH TIME ZONE, nullable; updated on each successful publish
--   registration_status — VARCHAR(32): 'REGISTERED' | 'UNREGISTERED' | 'ERROR'

CREATE TABLE info_portal_state (
    tenant_id             VARCHAR(255)  NOT NULL,
    location_id           VARCHAR(255)  NOT NULL,
    tournament_id         VARCHAR(255)  NOT NULL,
    last_published_seq    BIGINT        NOT NULL DEFAULT 0,
    tournament_token      VARCHAR(1024) NOT NULL,
    per_tournament_secret VARBINARY(64) NOT NULL,
    last_published_at     TIMESTAMP WITH TIME ZONE,
    registration_status   VARCHAR(32)   NOT NULL DEFAULT 'REGISTERED',
    CONSTRAINT pk_info_portal_state PRIMARY KEY (tenant_id, location_id, tournament_id)
);
