-- ============================================================
-- V5__e03_aggregates_and_audit.sql — Tournament Manager E03S04 aggregate and audit tables
-- Story:  E03S04
-- DECs:   DEC-5  (multi-tenant invariants, tenant_id NOT NULL)
--         DEC-7  (fresh rewrite — no legacy data migration)
--         DEC-14 (H2 + Flyway persistence; round_snapshots + audit_log explicit support)
--         DEC-17 (eager schema; tenant_id NOT NULL; no seed data)
-- ============================================================
--
-- This migration creates four supporting tables that close the E03 schema story set:
--
--   1. match_outcome     — D-32: 1:1 with Match, aggregated result across all SetResults
--   2. team_avatar_rating — D-33: 1:1 with TeamAvatar per phase, standings row
--   3. audit_log         — D-22/D-36: set-level correction history (append-only by convention)
--   4. round_snapshots   — D-2/D-10: structured snapshot of standings at lap-advance
--
-- Design decisions:
--   - All four tables carry tenant_id NOT NULL per DEC-5 / DEC-17.
--   - match_outcome uses match_id as UUID PRIMARY KEY (1:1 with Match).
--   - team_avatar_rating uses avatar_id as UUID PRIMARY KEY (1:1 with TeamAvatar per phase).
--   - audit_log uses a surrogate UUID PK for simple append semantics.
--   - round_snapshots uses a surrogate UUID PK; UNIQUE (tournament_id, phase_id, lap_number)
--     enforces the single-snapshot-per-lap invariant (AC5/AC9).
--   - snapshot_payload is CLOB for V1 simplicity per story notes; typed columns deferred to V2.
--   - audit_log UPDATE/DELETE is forbidden by repository-layer convention (AC13), not by trigger.
--   - set_quotient and ball_quotient are DOUBLE for sentinel-value support (see AC3 story notes).
--   - CHECK constraints ensure non-negative counts on match_outcome and team_avatar_rating.
--
-- No INSERT statements per DEC-17 / AC7.
-- All FK references use ON DELETE RESTRICT to preserve data integrity.
-- ============================================================

-- ============================================================
-- 1. match_outcome — AC2 (D-32)
--    1:1 with Match. Aggregated result populated by cascade step 4 (D-21).
--    Invariant: match_outcome.computed_state == match.state (enforced by cascade step 6, D-32).
-- ============================================================
CREATE TABLE match_outcome (
    match_id            UUID          NOT NULL,
    tenant_id           UUID          NOT NULL,
    team1_sets_won      INT           NOT NULL  DEFAULT 0,
    team1_balls_won     INT           NOT NULL  DEFAULT 0,
    team2_sets_won      INT           NOT NULL  DEFAULT 0,
    team2_balls_won     INT           NOT NULL  DEFAULT 0,
    set_count           INT           NOT NULL  DEFAULT 0,
    -- computed_state: legacy integer code from MatchState enum
    -- must always equal match.state per D-32 Match-state invariant
    computed_state      INT           NOT NULL,
    updated_at          TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    -- 1:1 with Match — match_id is both PK and FK
    CONSTRAINT pk_match_outcome PRIMARY KEY (match_id),

    CONSTRAINT fk_match_outcome_match
        FOREIGN KEY (match_id) REFERENCES match (id)
        ON DELETE RESTRICT,

    -- Tenant scope per DEC-5 / DEC-17
    CONSTRAINT fk_match_outcome_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
        ON DELETE RESTRICT,

    -- Counts and ball totals must be non-negative
    CONSTRAINT chk_match_outcome_team1_sets_won
        CHECK (team1_sets_won >= 0),
    CONSTRAINT chk_match_outcome_team1_balls_won
        CHECK (team1_balls_won >= 0),
    CONSTRAINT chk_match_outcome_team2_sets_won
        CHECK (team2_sets_won >= 0),
    CONSTRAINT chk_match_outcome_team2_balls_won
        CHECK (team2_balls_won >= 0),
    CONSTRAINT chk_match_outcome_set_count
        CHECK (set_count >= 0)
);

-- Tenant-scoped filter per D-34 / DEC-17
CREATE INDEX idx_match_outcome_tenant_id ON match_outcome (tenant_id);

-- ============================================================
-- 2. team_avatar_rating — AC3 (D-33)
--    1:1 with TeamAvatar per phase. Standings row for group table derivation.
--    Sort order: points DESC -> set_quotient DESC -> ball_quotient DESC
--                with is_without_assessment=TRUE rows forced last (D-26).
-- ============================================================
CREATE TABLE team_avatar_rating (
    avatar_id           UUID          NOT NULL,
    tenant_id           UUID          NOT NULL,
    match_count         INT           NOT NULL  DEFAULT 0,
    set_count           INT           NOT NULL  DEFAULT 0,
    points              INT           NOT NULL  DEFAULT 0,
    sets_won            INT           NOT NULL  DEFAULT 0,
    sets_lost           INT           NOT NULL  DEFAULT 0,
    balls_won           INT           NOT NULL  DEFAULT 0,
    balls_lost          INT           NOT NULL  DEFAULT 0,
    -- set_quotient: sets_won / sets_lost. Double.MAX_VALUE sentinel when sets_lost = 0 (no losses).
    set_quotient        DOUBLE        NOT NULL  DEFAULT 0,
    -- ball_quotient: balls_won / balls_lost. Double.MAX_VALUE sentinel when balls_lost = 0.
    ball_quotient       DOUBLE        NOT NULL  DEFAULT 0,
    -- is_without_assessment: copied from team.without_assessment at avatar creation time (D-26).
    -- If TRUE, this avatar sorts last in group table regardless of other scores.
    -- V1 does NOT auto-refresh if team.without_assessment changes mid-tournament (story notes).
    is_without_assessment BOOLEAN     NOT NULL  DEFAULT FALSE,
    updated_at          TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    -- 1:1 with TeamAvatar — avatar_id is both PK and FK
    CONSTRAINT pk_team_avatar_rating PRIMARY KEY (avatar_id),

    CONSTRAINT fk_team_avatar_rating_avatar
        FOREIGN KEY (avatar_id) REFERENCES team_avatar (id)
        ON DELETE RESTRICT,

    -- Tenant scope per DEC-5 / DEC-17
    CONSTRAINT fk_team_avatar_rating_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
        ON DELETE RESTRICT,

    -- All counts must be non-negative
    CONSTRAINT chk_team_avatar_rating_match_count
        CHECK (match_count >= 0),
    CONSTRAINT chk_team_avatar_rating_set_count
        CHECK (set_count >= 0),
    CONSTRAINT chk_team_avatar_rating_sets_won
        CHECK (sets_won >= 0),
    CONSTRAINT chk_team_avatar_rating_sets_lost
        CHECK (sets_lost >= 0),
    CONSTRAINT chk_team_avatar_rating_balls_won
        CHECK (balls_won >= 0),
    CONSTRAINT chk_team_avatar_rating_balls_lost
        CHECK (balls_lost >= 0)
);

-- Tenant-scoped filter per D-34 / DEC-17
CREATE INDEX idx_team_avatar_rating_tenant_id ON team_avatar_rating (tenant_id);

-- ============================================================
-- 3. audit_log — AC4 (D-22, D-36)
--    Set-level correction history. Append-only by convention (no trigger — see AC13).
--    Every SetResult INSERT or UPDATE appends one row with old and new values.
-- ============================================================
CREATE TABLE audit_log (
    id                  UUID          NOT NULL,
    tenant_id           UUID          NOT NULL,
    match_id            UUID          NOT NULL,
    set_index           INT           NOT NULL,
    -- old values: NULL on first INSERT of a set result (no prior state)
    team1_points_old    INT           NULL,
    team2_points_old    INT           NULL,
    set_state_old       INT           NULL,
    -- new values: always NOT NULL
    team1_points_new    INT           NOT NULL,
    team2_points_new    INT           NOT NULL,
    set_state_new       INT           NOT NULL,
    -- actor_id: who made the change (NULL in default-tenant LAN mode — no login required)
    actor_id            VARCHAR       NULL,
    -- reason: manual explanation if provided by organiser
    reason              VARCHAR       NULL,
    changed_at          TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_audit_log PRIMARY KEY (id),

    -- Tenant scope per DEC-5 / DEC-17
    CONSTRAINT fk_audit_log_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
        ON DELETE RESTRICT,

    -- Match FK — every audit entry belongs to exactly one match
    CONSTRAINT fk_audit_log_match
        FOREIGN KEY (match_id) REFERENCES match (id)
        ON DELETE RESTRICT
);

-- Index on (match_id, set_index, changed_at) supports "show me the history of corrections
-- for this set" query (AC4 story requirement).
CREATE INDEX idx_audit_log_match_set_time ON audit_log (match_id, set_index, changed_at);

-- Tenant-scoped filter per D-34 / DEC-17
CREATE INDEX idx_audit_log_tenant_id ON audit_log (tenant_id);

-- ============================================================
-- 4. round_snapshots — AC5 (D-2, D-10)
--    Structured snapshot of a phase's standings at lap-advance.
--    Populated by E03S13 (round-end snapshot service).
--    Consumed by E08 (print output) and E07 (overview UI).
-- ============================================================
CREATE TABLE round_snapshots (
    id                  UUID          NOT NULL,
    tenant_id           UUID          NOT NULL,
    tournament_id       UUID          NOT NULL,
    phase_id            UUID          NOT NULL,
    lap_number          INT           NOT NULL,
    -- snapshot_payload: JSON serialization of standings at this lap boundary.
    -- CLOB for V1 simplicity; typed columns may be added in a future migration if
    -- richer snapshot queries are needed (story notes).
    -- Exact JSON schema is a Delivery decision — documented in the E03S04 impl-report.
    snapshot_payload    CLOB          NOT NULL,
    created_at          TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_round_snapshots PRIMARY KEY (id),

    -- Tenant scope per DEC-5 / DEC-17
    CONSTRAINT fk_round_snapshots_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
        ON DELETE RESTRICT,

    -- Tournament FK
    CONSTRAINT fk_round_snapshots_tournament
        FOREIGN KEY (tournament_id) REFERENCES tournament (id)
        ON DELETE RESTRICT,

    -- Phase FK
    CONSTRAINT fk_round_snapshots_phase
        FOREIGN KEY (phase_id) REFERENCES phase (id)
        ON DELETE RESTRICT,

    -- AC5 / AC9: exactly one snapshot per (tournament, phase, lap)
    CONSTRAINT uq_round_snapshots_tournament_phase_lap
        UNIQUE (tournament_id, phase_id, lap_number)
);

-- Tenant-scoped filter per D-34 / DEC-17
CREATE INDEX idx_round_snapshots_tenant_id ON round_snapshots (tenant_id);
