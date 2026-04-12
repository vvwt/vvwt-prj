-- ============================================================
-- V3__e03_match.sql — Tournament Manager E03 Match entity
-- Story:  E03S02
-- DECs:   DEC-5  (multi-tenant invariants, tenant_id NOT NULL)
--         DEC-7  (fresh rewrite — no legacy data migration)
--         DEC-9  (Matches reference TeamAvatars, not Teams)
--         DEC-14 (H2 + Flyway persistence)
--         DEC-17 (eager schema; tenant_id NOT NULL; no seed data)
-- ============================================================
--
-- This migration creates the `match` table — the central operational
-- entity of the tournament domain.
--
-- Design decisions:
--   - state stored as INT with CHECK constraint (legacy enum codes)
--   - lap_number and field_number are nullable (filled by E04 slot-opt)
--   - member_avatar_1_id / member_avatar_2_id reference team_avatar (DEC-9)
--   - referee_preference_config stored as CLOB (H2 equivalent of JSONB)
--   - referee_team_id references team (the team assigned to referee, not avatar)
--
-- No INSERT statements per DEC-17 / AC8.
-- All FK references use ON DELETE RESTRICT to preserve data integrity.
-- ============================================================

-- ------------------------------------------------------------
-- match
-- AC2 — core columns (all NOT NULL except slot coordinates and referee)
-- AC3 — nullable slot coordinates: lap_number, field_number
-- AC4 — referee columns: referee_team_id (nullable FK), referee_description, referee_preference_config
-- AC5 — CHECK constraint on state (legacy enum codes: 0, 10, 30, 35, 50, 51, 52, -10)
-- ------------------------------------------------------------
CREATE TABLE match (
    id                          UUID          NOT NULL,
    tenant_id                   UUID          NOT NULL,
    tournament_id               UUID          NOT NULL,
    phase_id                    UUID          NOT NULL,
    member_avatar_1_id          UUID          NOT NULL,
    member_avatar_2_id          UUID          NOT NULL,
    state                       INT           NOT NULL,
    set_limit                   INT           NOT NULL  DEFAULT 1,
    -- AC3: nullable slot coordinates — NULL = "not yet scheduled" (filled by E04 slot-opt)
    lap_number                  INT           NULL,
    field_number                INT           NULL,
    -- AC4: referee columns — all nullable; populated by RefereeAssigner (E03S10)
    referee_team_id             UUID          NULL,
    referee_description         VARCHAR       NULL,
    -- H2 CLOB stores large text (JSONB equivalent); E03S10 documents serialization format
    referee_preference_config   CLOB          NULL,
    created_at                  TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_match PRIMARY KEY (id),

    -- Tenant scope per DEC-5 / DEC-17 (AC2, AC12)
    CONSTRAINT fk_match_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id)
        ON DELETE RESTRICT,

    -- Tournament membership (AC2)
    CONSTRAINT fk_match_tournament
        FOREIGN KEY (tournament_id) REFERENCES tournament (id)
        ON DELETE RESTRICT,

    -- Phase membership — NOT NULL (every match belongs to a phase) (AC2)
    CONSTRAINT fk_match_phase
        FOREIGN KEY (phase_id) REFERENCES phase (id)
        ON DELETE RESTRICT,

    -- DEC-9: TeamAvatar references (not Team) — the structural identity for matches
    CONSTRAINT fk_match_member_avatar_1
        FOREIGN KEY (member_avatar_1_id) REFERENCES team_avatar (id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_match_member_avatar_2
        FOREIGN KEY (member_avatar_2_id) REFERENCES team_avatar (id)
        ON DELETE RESTRICT,

    -- Referee team reference — nullable FK (populated by E03S10) (AC4)
    CONSTRAINT fk_match_referee_team
        FOREIGN KEY (referee_team_id) REFERENCES team (id)
        ON DELETE RESTRICT,

    -- AC5: legacy enum codes only. FINISHED_STANDOFF(50) only reachable with FIXED_2_SETS format.
    -- Codes: OPEN(0), ENABLED(10), INPROGRESS(30), ONCHECK(35),
    --        FINISHED_STANDOFF(50), FINISHED_WINNER1(51), FINISHED_WINNER2(52), CANCELED(-10)
    CONSTRAINT chk_match_state
        CHECK (state IN (0, 10, 30, 35, 50, 51, 52, -10))
);

-- AC6: Indexes for expected query patterns

-- "All matches in a phase" — used by match distribution and phase-prep queries
CREATE INDEX idx_match_tournament_phase ON match (tournament_id, phase_id);

-- "All matches in a lap" — used by auto-lap-advance query in cascade service (D-21 step 10)
CREATE INDEX idx_match_phase_lap ON match (phase_id, lap_number);

-- "All matches this team plays" — avatar rating refresh (D-21 steps 7-8)
CREATE INDEX idx_match_member_avatar_1 ON match (member_avatar_1_id);
CREATE INDEX idx_match_member_avatar_2 ON match (member_avatar_2_id);

-- Tenant-scoped filter per D-34 / DEC-17
CREATE INDEX idx_match_tenant_id ON match (tenant_id);
