-- ============================================================
-- tournament/V1__initial_schema.sql — Tournament context schema
-- Epic:  E45 (Wave-2 Big-Bang-Reset) + E46S06 (DEC-52 V2→V1 i18n consolidation)
-- Story: E45S05, E46S06
-- DECs:  DEC-20 (DB-per-Tenant; tenant-scoped tables in per-tenant H2 file)
--        DEC-25 (Wave-2 Big-Bang-Reset: single atomic commit, reconstructed-from-scratch;
--                no-prod-data condition authorises E46S06 consolidation per DEC-52)
--        DEC-39 D1 (tenant_id removed from all enumerated tenant-scoped tables)
--        DEC-39 D2 (tournament.location_id UUID NOT NULL, FK to tenant/locations(id))
--        DEC-39 D3 (active_sentinel re-scoped per location, not per tenant)
--        DEC-5  (at most one ACTIVE tournament per location per DEC-39 D3)
--        DEC-9  (TeamAvatar structural identity via (phase, group, position))
--        DEC-14 (H2 + Flyway; CLOB for JSON payloads)
--        DEC-21 (Spring Modulith Flyway-layout: tournament depends on tenant;
--                cross-module FK tournament.location_id -> tenant/locations(id)
--                valid because tournament/package-info.java declares
--                allowedDependencies = {"tenant"} — tenant/V1 applied before tournament/V1)
--        DEC-24 (devices.location_id nullable: post-registration assignment)
--        DEC-52 (E46S01 V2 i18n columns retroactively reclassified as V1 baseline;
--                organizer + language columns inlined per DEC-52 §Reclassification — E46S06)
-- ============================================================
--
-- Consolidates root V2, V3, V4, V5, V7, V8, V9, V10, V11, V12, V13, V14, V16
-- into a single per-module V1, reconstructed-from-scratch per DEC-25 §Decision.
--
-- E46S06 (DEC-52): Three columns were originally introduced as
-- tournament/V2__e46s01_certificate_default_template_i18n.sql by E46S01 (PR #178).
-- DEC-52 retroactively reclassifies them as part of the V1 baseline (i18n is a
-- foundational requirement of the application rewrite). The V2 file is deleted;
-- columns are inlined here. See git log for V2 provenance.
--
-- Key differences from root migrations:
--   - All tenant_id columns DROPPED from all tables (DEC-39 D1)
--   - tournament.location_id UUID NOT NULL added (DEC-39 D2)
--   - active_sentinel = location_id (not tenant_id) (DEC-39 D3)
--   - All idx_*_tenant_id indexes DROPPED
--   - Cross-module FKs to tenant/locations(id) (DEC-21 Flyway ordering)
--
-- Table creation order respects FK dependencies:
--   tournament → phase → team → team_avatar → match → set_result →
--   match_outcome → team_avatar_rating → audit_log → round_snapshots →
--   phase_breaks → activity_types → devices
-- ============================================================

-- ============================================================
-- tournament
-- DEC-39 D2: location_id UUID NOT NULL FK to tenant/locations(id)
-- DEC-39 D3: active_sentinel = location_id for ACTIVE, NULL otherwise
--            Enforces: at most one ACTIVE tournament per location
-- V7 columns: appointment, field_count, team_count (included here — reconstructed-from-scratch)
-- V11 column: planned_start_time TIME NULL
-- V14 column: draft_json TEXT
-- DEC-52 (E46S06): organizer + language columns inlined from former tournament/V2 (E46S01)
-- ============================================================
CREATE TABLE tournament (
    id                      UUID          NOT NULL,
    -- DEC-39 D2: location_id NOT NULL; FK to tenant/locations(id)
    -- Cross-module FK valid per DEC-21 Flyway dependency-tree ordering:
    -- tournament.allowedDependencies = {"tenant"} ensures tenant/V1 runs before tournament/V1.
    location_id             UUID          NOT NULL,
    description             VARCHAR       NOT NULL,
    match_format            VARCHAR       NOT NULL,
    scoring_rule_id         VARCHAR       NOT NULL,
    set_validation_rule_id  VARCHAR       NOT NULL,
    match_generator_id      VARCHAR       NOT NULL,
    status                  VARCHAR       NOT NULL  DEFAULT 'DRAFT',
    created_at              TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    -- V7 (E05S04): tournament date, field count, team count
    appointment             TIMESTAMP     DEFAULT NULL,
    field_count             INT           NOT NULL DEFAULT 1,
    team_count              INT           NOT NULL DEFAULT 2,
    -- V11 (E08S01): planned start time (time-of-day)
    planned_start_time      TIME          NULL,
    -- V14 (E08S05): draft configuration as JSON text
    draft_json              TEXT          DEFAULT NULL,
    -- DEC-52 (E46S06): organizer column inlined from former tournament/V2 (E46S01).
    -- Snapshot of tenants.display_name captured at tournament INSERT time.
    -- No DEFAULT clause — value computed by application code (DefaultTournamentRepository.save()).
    -- No index, no FK, no check constraint (pure data-carrier per E46S01 AC-INSERT-NO-DEFAULT-CLAUSE).
    organizer               VARCHAR(255)  NULL,
    -- DEC-52 (E46S06): language column inlined from former tournament/V2 (E46S01).
    -- Per-tournament language override for the LocaleResolver chain (E46S02).
    -- NULL semantics: fall through to tenants.language ?? default 'de'.
    -- No DEFAULT clause, no index, no FK, no check constraint.
    language                VARCHAR(8)    NULL,
    -- DEC-39 D3: active_sentinel = location_id for ACTIVE, NULL otherwise.
    -- Enforces: at most one ACTIVE tournament per location within this per-tenant file.
    -- NULLs (non-ACTIVE tournaments) are distinct in UNIQUE — multiple non-active coexist.
    active_sentinel         UUID          GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN location_id ELSE NULL END),
    CONSTRAINT pk_tournament PRIMARY KEY (id),
    CONSTRAINT fk_tournament_location
        FOREIGN KEY (location_id) REFERENCES locations (id)
        ON DELETE RESTRICT
);

-- DEC-39 D3: at most one ACTIVE tournament per location.
CREATE UNIQUE INDEX idx_tournament_active_per_location ON tournament (active_sentinel);


-- ============================================================
-- phase
-- ============================================================
CREATE TABLE phase (
    id                  UUID          NOT NULL,
    tournament_id       UUID          NOT NULL,
    sequence_number     INT           NOT NULL,
    description         VARCHAR       NOT NULL,
    status              VARCHAR       NOT NULL,
    current_lap_number  INT           NOT NULL  DEFAULT 0,
    created_at          TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_phase PRIMARY KEY (id),
    CONSTRAINT fk_phase_tournament
        FOREIGN KEY (tournament_id) REFERENCES tournament (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_phase_tournament_sequence
        UNIQUE (tournament_id, sequence_number)
);


-- ============================================================
-- team
-- DEC-52 (E46S06): language column inlined from former tournament/V2 (E46S01).
-- ============================================================
CREATE TABLE team (
    id                  UUID          NOT NULL,
    tournament_id       UUID          NOT NULL,
    team_number         INT           NOT NULL,
    description         VARCHAR       NOT NULL,
    participate         BOOLEAN       NOT NULL  DEFAULT TRUE,
    referee_assignment  BOOLEAN       NOT NULL  DEFAULT FALSE,
    without_assessment  BOOLEAN       NOT NULL  DEFAULT FALSE,
    created_at          TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    -- DEC-52 (E46S06): language column inlined from former tournament/V2 (E46S01).
    -- Per-team language override for the LocaleResolver chain (E46S02).
    -- NULL semantics: fall through to tournament.language ?? tenants.language ?? 'de'.
    -- No DEFAULT clause, no index, no FK, no check constraint.
    language            VARCHAR(8)    NULL,
    CONSTRAINT pk_team PRIMARY KEY (id),
    CONSTRAINT fk_team_tournament
        FOREIGN KEY (tournament_id) REFERENCES tournament (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_team_tournament_number
        UNIQUE (tournament_id, team_number)
);


-- ============================================================
-- team_avatar
-- DEC-9: structural identity UNIQUE (tournament_id, phase_id, group_number, group_position)
-- ============================================================
CREATE TABLE team_avatar (
    id              UUID          NOT NULL,
    tournament_id   UUID          NOT NULL,
    phase_id        UUID          NOT NULL,
    group_number    INT           NOT NULL,
    group_position  INT           NOT NULL,
    team_id         UUID          NOT NULL,
    description     VARCHAR,
    created_at      TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_team_avatar PRIMARY KEY (id),
    CONSTRAINT fk_team_avatar_tournament
        FOREIGN KEY (tournament_id) REFERENCES tournament (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_team_avatar_phase
        FOREIGN KEY (phase_id) REFERENCES phase (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_team_avatar_team
        FOREIGN KEY (team_id) REFERENCES team (id)
        ON DELETE RESTRICT,
    -- DEC-9: structural identity — (tournament, phase, group, position) uniquely identifies a slot
    CONSTRAINT uq_team_avatar_structural_identity
        UNIQUE (tournament_id, phase_id, group_number, group_position)
);


-- ============================================================
-- match
-- ============================================================
CREATE TABLE match (
    id                          UUID          NOT NULL,
    tournament_id               UUID          NOT NULL,
    phase_id                    UUID          NOT NULL,
    member_avatar_1_id          UUID          NOT NULL,
    member_avatar_2_id          UUID          NOT NULL,
    state                       INT           NOT NULL,
    set_limit                   INT           NOT NULL  DEFAULT 1,
    lap_number                  INT           NULL,
    field_number                INT           NULL,
    referee_team_id             UUID          NULL,
    referee_description         VARCHAR       NULL,
    referee_preference_config   CLOB          NULL,
    created_at                  TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_match PRIMARY KEY (id),
    CONSTRAINT fk_match_tournament
        FOREIGN KEY (tournament_id) REFERENCES tournament (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_match_phase
        FOREIGN KEY (phase_id) REFERENCES phase (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_match_member_avatar_1
        FOREIGN KEY (member_avatar_1_id) REFERENCES team_avatar (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_match_member_avatar_2
        FOREIGN KEY (member_avatar_2_id) REFERENCES team_avatar (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_match_referee_team
        FOREIGN KEY (referee_team_id) REFERENCES team (id)
        ON DELETE RESTRICT,
    -- Match state: OPEN(0), ENABLED(10), INPROGRESS(30), ONCHECK(35),
    --              FINISHED_STANDOFF(50), FINISHED_WINNER1(51), FINISHED_WINNER2(52), CANCELED(-10)
    CONSTRAINT chk_match_state
        CHECK (state IN (0, 10, 30, 35, 50, 51, 52, -10))
);

-- Indexes for expected query patterns
CREATE INDEX idx_match_tournament_phase ON match (tournament_id, phase_id);
CREATE INDEX idx_match_phase_lap ON match (phase_id, lap_number);
CREATE INDEX idx_match_member_avatar_1 ON match (member_avatar_1_id);
CREATE INDEX idx_match_member_avatar_2 ON match (member_avatar_2_id);


-- ============================================================
-- set_result
-- Composite PK (match_id, set_index) per DEC-14 + E03S03 design
-- ============================================================
CREATE TABLE set_result (
    match_id            UUID          NOT NULL,
    set_index           INT           NOT NULL,
    phase_id            UUID          NOT NULL,
    team1_points        INT           NOT NULL,
    team2_points        INT           NOT NULL,
    set_state           INT           NOT NULL,
    change_time         TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    created_at          TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_set_result PRIMARY KEY (match_id, set_index),
    CONSTRAINT fk_set_result_match
        FOREIGN KEY (match_id) REFERENCES match (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_set_result_phase
        FOREIGN KEY (phase_id) REFERENCES phase (id)
        ON DELETE RESTRICT,
    -- Set state: OPEN(0), WINNER1(1), WINNER2(2), STANDOFF(3), CANCELED(-1)
    CONSTRAINT chk_set_result_state
        CHECK (set_state IN (0, 1, 2, 3, -1)),
    CONSTRAINT chk_set_result_team1_points
        CHECK (team1_points >= 0),
    CONSTRAINT chk_set_result_team2_points
        CHECK (team2_points >= 0)
);

CREATE INDEX idx_set_result_phase_state ON set_result (phase_id, set_state);


-- ============================================================
-- match_outcome
-- 1:1 with Match (match_id is PK)
-- ============================================================
CREATE TABLE match_outcome (
    match_id            UUID          NOT NULL,
    team1_sets_won      INT           NOT NULL  DEFAULT 0,
    team1_balls_won     INT           NOT NULL  DEFAULT 0,
    team2_sets_won      INT           NOT NULL  DEFAULT 0,
    team2_balls_won     INT           NOT NULL  DEFAULT 0,
    set_count           INT           NOT NULL  DEFAULT 0,
    computed_state      INT           NOT NULL,
    updated_at          TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_match_outcome PRIMARY KEY (match_id),
    CONSTRAINT fk_match_outcome_match
        FOREIGN KEY (match_id) REFERENCES match (id)
        ON DELETE RESTRICT,
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


-- ============================================================
-- team_avatar_rating
-- 1:1 with TeamAvatar (avatar_id is PK)
-- ============================================================
CREATE TABLE team_avatar_rating (
    avatar_id             UUID          NOT NULL,
    match_count           INT           NOT NULL  DEFAULT 0,
    set_count             INT           NOT NULL  DEFAULT 0,
    points                INT           NOT NULL  DEFAULT 0,
    sets_won              INT           NOT NULL  DEFAULT 0,
    sets_lost             INT           NOT NULL  DEFAULT 0,
    balls_won             INT           NOT NULL  DEFAULT 0,
    balls_lost            INT           NOT NULL  DEFAULT 0,
    set_quotient          DOUBLE        NOT NULL  DEFAULT 0,
    ball_quotient         DOUBLE        NOT NULL  DEFAULT 0,
    is_without_assessment BOOLEAN       NOT NULL  DEFAULT FALSE,
    updated_at            TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_team_avatar_rating PRIMARY KEY (avatar_id),
    CONSTRAINT fk_team_avatar_rating_avatar
        FOREIGN KEY (avatar_id) REFERENCES team_avatar (id)
        ON DELETE RESTRICT,
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


-- ============================================================
-- audit_log
-- Append-only set-level correction history (V5 + V9 source_type/source_device_id)
-- ============================================================
CREATE TABLE audit_log (
    id                  UUID          NOT NULL,
    match_id            UUID          NOT NULL,
    set_index           INT           NOT NULL,
    team1_points_old    INT           NULL,
    team2_points_old    INT           NULL,
    set_state_old       INT           NULL,
    team1_points_new    INT           NOT NULL,
    team2_points_new    INT           NOT NULL,
    set_state_new       INT           NOT NULL,
    actor_id            VARCHAR       NULL,
    reason              VARCHAR       NULL,
    changed_at          TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    -- V9 (E06S06): source tracking
    source_type         VARCHAR(32)   NULL,
    source_device_id    VARCHAR(128)  NULL,
    CONSTRAINT pk_audit_log PRIMARY KEY (id),
    CONSTRAINT fk_audit_log_match
        FOREIGN KEY (match_id) REFERENCES match (id)
        ON DELETE RESTRICT
);

CREATE INDEX idx_audit_log_match_set_time ON audit_log (match_id, set_index, changed_at);
CREATE INDEX idx_audit_log_match_source ON audit_log (match_id, source_type);


-- ============================================================
-- round_snapshots
-- Structured snapshot of standings at lap-advance
-- UNIQUE (tournament_id, phase_id, lap_number) — one snapshot per lap
-- ============================================================
CREATE TABLE round_snapshots (
    id                  UUID          NOT NULL,
    tournament_id       UUID          NOT NULL,
    phase_id            UUID          NOT NULL,
    lap_number          INT           NOT NULL,
    snapshot_payload    CLOB          NOT NULL,
    created_at          TIMESTAMP     NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_round_snapshots PRIMARY KEY (id),
    CONSTRAINT fk_round_snapshots_tournament
        FOREIGN KEY (tournament_id) REFERENCES tournament (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_round_snapshots_phase
        FOREIGN KEY (phase_id) REFERENCES phase (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_round_snapshots_tournament_phase_lap
        UNIQUE (tournament_id, phase_id, lap_number)
);


-- ============================================================
-- phase_breaks
-- Intra-phase break configuration (V12 / E08S01)
-- ============================================================
CREATE TABLE phase_breaks (
    id                  UUID          NOT NULL,
    phase_id            UUID          NOT NULL,
    after_lap_number    INT           NOT NULL,
    duration_minutes    INT           NOT NULL,
    label               VARCHAR       NULL,
    CONSTRAINT pk_phase_breaks
        PRIMARY KEY (id),
    CONSTRAINT fk_phase_breaks_phase
        FOREIGN KEY (phase_id) REFERENCES phase (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_phase_breaks_phase_lap
        UNIQUE (phase_id, after_lap_number),
    CONSTRAINT chk_phase_breaks_duration
        CHECK (duration_minutes > 0)
);

CREATE INDEX idx_phase_breaks_phase_id ON phase_breaks (phase_id);


-- ============================================================
-- activity_types
-- Organizer-defined activity configurations (V13 / E08S02)
-- Note: activity_types Java code relocated to de.vvwt.tm.tournament.activity at E45S01
-- ============================================================
CREATE TABLE activity_types (
    id                   UUID          NOT NULL,
    tournament_id        UUID          NOT NULL,
    name                 VARCHAR       NOT NULL,
    assignment_rule      VARCHAR       NOT NULL,
    capacity_per_round   INT,
    sort_order           INT           NOT NULL,
    CONSTRAINT pk_activity_types
        PRIMARY KEY (id),
    CONSTRAINT fk_activity_types_tournament
        FOREIGN KEY (tournament_id) REFERENCES tournament (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_activity_types_tournament_name
        UNIQUE (tournament_id, name),
    CONSTRAINT chk_activity_types_capacity
        CHECK (capacity_per_round IS NULL OR capacity_per_round > 0)
);

CREATE INDEX idx_activity_types_tournament_id ON activity_types (tournament_id);


-- ============================================================
-- devices
-- Device registration (V8 + V10 + V16 consolidated)
-- DEC-24: devices.location_id is NULLABLE (post-registration location assignment)
-- Cross-module FK: devices.location_id -> tenant/locations(id)
-- ============================================================
CREATE TABLE devices (
    id              UUID         NOT NULL,
    device_token    VARCHAR(128) NOT NULL,
    pin             VARCHAR(8)   NULL,
    device_type     VARCHAR(32)  NOT NULL  DEFAULT 'SCORING_TABLET',
    assigned_field  INT,
    status          VARCHAR(32)  NOT NULL  DEFAULT 'REGISTERED',
    registered_at   TIMESTAMP    NOT NULL  DEFAULT CURRENT_TIMESTAMP,
    last_seen_at    TIMESTAMP,
    -- V10 (E07S01): display device support
    device_name     VARCHAR(255),
    configuration   TEXT,
    -- DEC-24: location_id nullable — devices register first, assigned to location later
    -- Cross-module FK to tenant/locations(id) (valid per DEC-21: tournament allowedDependencies={"tenant"})
    location_id     UUID         NULL,
    CONSTRAINT pk_devices PRIMARY KEY (id),
    CONSTRAINT fk_devices_location
        FOREIGN KEY (location_id) REFERENCES locations (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_devices_token
        UNIQUE (device_token),
    -- PIN unique within the DB file (per-tenant DB = one tenant); NULL PINs are distinct per SQL standard
    CONSTRAINT uq_devices_pin
        UNIQUE (pin)
);

-- Fast lookup by device_token
CREATE INDEX idx_devices_token ON devices (device_token);
