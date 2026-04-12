-- ============================================================
-- V2__e03_core_schema.sql — Tournament Manager E03 core schema
-- Story:  E03S01
-- DECs:   DEC-5  (multi-tenant invariants, active-tournament constraint)
--         DEC-7  (fresh rewrite — no legacy data migration)
--         DEC-9  (TeamAvatar structural identity)
--         DEC-10 (Java 21 + Spring Boot 4.0.5)
--         DEC-14 (H2 + Flyway persistence)
--         DEC-17 (eager schema; tenant_id NOT NULL; no seed data)
-- ============================================================
--
-- This migration creates the four foundational tournament-domain tables:
--   - tournament   (root aggregate — DEC-5 active-tournament invariant)
--   - phase        (first-class entity — D-18, D-35)
--   - team         (tournament-scoped — D-20)
--   - team_avatar  (structural identity — DEC-9)
--
-- No INSERT statements are present per DEC-17 / AC7.
-- All four tables carry tenant_id UUID NOT NULL per D-34 and DEC-17.
--
-- DEC-5 active-tournament invariant (AC2):
--   "The default tenant is restricted to exactly one active tournament at a time."
--   H2 2.3.232 does NOT support partial/filtered unique indexes (CREATE UNIQUE INDEX ... WHERE).
--   Resolution: generated column `active_sentinel` is ALWAYS NULL when status != 'ACTIVE'
--   and equals tenant_id when status = 'ACTIVE'. A plain UNIQUE index on active_sentinel
--   enforces at most one ACTIVE tournament per tenant (NULLs are not equal in UNIQUE constraints).
--   This is strictly stronger than required (applies to all tenants), but is safe and correct.
--
-- DEC-9 structural identity (AC5):
--   UNIQUE constraint on (tournament_id, phase_id, group_number, group_position) on team_avatar
--   makes the position-triple the structural identity per the optimizer service boundary.
-- ============================================================

-- ------------------------------------------------------------
-- tournament
-- AC2 columns (mandatory):
--   id                      UUID PRIMARY KEY
--   tenant_id               UUID NOT NULL REFERENCES tenants(id)
--   description             VARCHAR NOT NULL
--   match_format            VARCHAR NOT NULL  (stores enum name: BEST_OF_1 / _3 / _5 / _7 / FIXED_2_SETS)
--   scoring_rule_id         VARCHAR NOT NULL  (Spring bean id — D-15)
--   set_validation_rule_id  VARCHAR NOT NULL  (Spring bean id — D-16)
--   match_generator_id      VARCHAR NOT NULL  (Spring bean id — D-27)
--   status                  VARCHAR NOT NULL DEFAULT 'DRAFT'
--   created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
--
-- DEC-5 invariant enforcement:
--   active_sentinel: GENERATED column — equals tenant_id when status='ACTIVE', NULL otherwise.
--   UNIQUE index on active_sentinel enforces at most one ACTIVE tournament per tenant.
-- ------------------------------------------------------------
CREATE TABLE tournament (
    id                      UUID          NOT NULL,
    tenant_id               UUID          NOT NULL,
    description             VARCHAR       NOT NULL,
    match_format            VARCHAR       NOT NULL,
    scoring_rule_id         VARCHAR       NOT NULL,
    set_validation_rule_id  VARCHAR       NOT NULL,
    match_generator_id      VARCHAR       NOT NULL,
    status                  VARCHAR       NOT NULL  DEFAULT 'DRAFT',
    created_at              TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    -- DEC-5: generated sentinel for active-tournament-per-tenant uniqueness.
    -- NULL for non-ACTIVE tournaments (multiple NULLs allowed);
    -- equals tenant_id for ACTIVE tournaments (enforced unique — at most one per tenant).
    active_sentinel         UUID          GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN tenant_id ELSE NULL END),
    CONSTRAINT pk_tournament PRIMARY KEY (id),
    CONSTRAINT fk_tournament_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
        ON DELETE RESTRICT
);

-- DEC-5 active-tournament invariant: at most one ACTIVE tournament per tenant.
-- active_sentinel = tenant_id for ACTIVE, NULL for all others.
-- NULLs are not considered equal in UNIQUE constraints (SQL standard).
CREATE UNIQUE INDEX idx_tournament_active_per_tenant ON tournament (active_sentinel);

-- Fast tenant-scoped tournament queries (D-34, DEC-17)
CREATE INDEX idx_tournament_tenant_id ON tournament (tenant_id);


-- ------------------------------------------------------------
-- phase
-- AC3 columns (mandatory):
--   id                UUID PRIMARY KEY
--   tenant_id         UUID NOT NULL REFERENCES tenants(id)
--   tournament_id     UUID NOT NULL REFERENCES tournament(id)
--   sequence_number   INT NOT NULL
--   description       VARCHAR NOT NULL
--   status            VARCHAR NOT NULL  (PENDING / ACTIVE / COMPLETED — D-35)
--   current_lap_number INT NOT NULL DEFAULT 0
--   created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
--
-- UNIQUE (tournament_id, sequence_number) — unambiguous phase ordering
-- ------------------------------------------------------------
CREATE TABLE phase (
    id                  UUID          NOT NULL,
    tenant_id           UUID          NOT NULL,
    tournament_id       UUID          NOT NULL,
    sequence_number     INT           NOT NULL,
    description         VARCHAR       NOT NULL,
    status              VARCHAR       NOT NULL,
    current_lap_number  INT           NOT NULL  DEFAULT 0,
    created_at          TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_phase PRIMARY KEY (id),
    CONSTRAINT fk_phase_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_phase_tournament
        FOREIGN KEY (tournament_id) REFERENCES tournament (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_phase_tournament_sequence
        UNIQUE (tournament_id, sequence_number)
);

-- Fast tenant-scoped phase queries (D-34, DEC-17)
CREATE INDEX idx_phase_tenant_id ON phase (tenant_id);


-- ------------------------------------------------------------
-- team
-- AC4 columns (mandatory):
--   id                  UUID PRIMARY KEY
--   tenant_id           UUID NOT NULL REFERENCES tenants(id)
--   tournament_id       UUID NOT NULL REFERENCES tournament(id)
--   team_number         INT NOT NULL
--   description         VARCHAR NOT NULL
--   participate         BOOLEAN NOT NULL DEFAULT TRUE
--   referee_assignment  BOOLEAN NOT NULL DEFAULT FALSE
--   without_assessment  BOOLEAN NOT NULL DEFAULT FALSE
--   created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
--
-- UNIQUE (tournament_id, team_number) — team numbers unique within a tournament
-- ------------------------------------------------------------
CREATE TABLE team (
    id                  UUID          NOT NULL,
    tenant_id           UUID          NOT NULL,
    tournament_id       UUID          NOT NULL,
    team_number         INT           NOT NULL,
    description         VARCHAR       NOT NULL,
    participate         BOOLEAN       NOT NULL  DEFAULT TRUE,
    referee_assignment  BOOLEAN       NOT NULL  DEFAULT FALSE,
    without_assessment  BOOLEAN       NOT NULL  DEFAULT FALSE,
    created_at          TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_team PRIMARY KEY (id),
    CONSTRAINT fk_team_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_team_tournament
        FOREIGN KEY (tournament_id) REFERENCES tournament (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_team_tournament_number
        UNIQUE (tournament_id, team_number)
);

-- Fast tenant-scoped team queries (D-34, DEC-17)
CREATE INDEX idx_team_tenant_id ON team (tenant_id);


-- ------------------------------------------------------------
-- team_avatar
-- AC5 columns (mandatory):
--   id              UUID PRIMARY KEY
--   tenant_id       UUID NOT NULL REFERENCES tenants(id)
--   tournament_id   UUID NOT NULL REFERENCES tournament(id)
--   phase_id        UUID NOT NULL REFERENCES phase(id)
--   group_number    INT NOT NULL
--   group_position  INT NOT NULL
--   team_id         UUID NOT NULL REFERENCES team(id)
--   description     VARCHAR  (nullable — human label like "Gruppe A, Platz 1")
--   created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
--
-- DEC-9: UNIQUE (tournament_id, phase_id, group_number, group_position)
--        This is the structural identity for slot optimization and match references.
-- ------------------------------------------------------------
CREATE TABLE team_avatar (
    id              UUID          NOT NULL,
    tenant_id       UUID          NOT NULL,
    tournament_id   UUID          NOT NULL,
    phase_id        UUID          NOT NULL,
    group_number    INT           NOT NULL,
    group_position  INT           NOT NULL,
    team_id         UUID          NOT NULL,
    description     VARCHAR,
    created_at      TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_team_avatar PRIMARY KEY (id),
    CONSTRAINT fk_team_avatar_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_team_avatar_tournament
        FOREIGN KEY (tournament_id) REFERENCES tournament (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_team_avatar_phase
        FOREIGN KEY (phase_id) REFERENCES phase (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_team_avatar_team
        FOREIGN KEY (team_id) REFERENCES team (id)
        ON DELETE RESTRICT,
    -- DEC-9: structural identity — (tournament, phase, group, position) uniquely identifies a slot.
    CONSTRAINT uq_team_avatar_structural_identity
        UNIQUE (tournament_id, phase_id, group_number, group_position)
);

-- Fast tenant-scoped team_avatar queries (D-34, DEC-17)
CREATE INDEX idx_team_avatar_tenant_id ON team_avatar (tenant_id);
