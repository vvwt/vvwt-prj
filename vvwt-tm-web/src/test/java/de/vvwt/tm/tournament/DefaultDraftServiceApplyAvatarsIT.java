package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftSection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.db.type.AssertDbConnection;
import org.assertj.db.type.AssertDbConnectionFactory;
import org.assertj.db.type.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * RED-first DAO IT for {@link DraftService#apply(UUID, DraftConfig)} — verifies E51S02 avatar
 * persistence at DraftConfig-Apply time per DEC-55 D-1.
 *
 * <h2>DEC-26/DEC-46 three-rule conformance</h2>
 *
 * <ul>
 *   <li>Rule 1: Schema from Flyway migration (Spring manages schema via {@code @SpringBootTest})
 *   <li>Rule 2: assertj-db as independent persistence verifier (NOT draftService read-path)
 *   <li>Rule 3: Fixture data inserted via direct JDBC (not via draftService)
 * </ul>
 *
 * <h2>DEC-36 cross-package typing</h2>
 *
 * <p>Tests are in package {@code de.vvwt.tm.tournament} — the same package as the public interface
 * {@link DraftService}. Injection uses the public interface type per DEC-36. The concrete {@code
 * DefaultDraftService} is never referenced.
 *
 * <h2>DEC-22 RED-first governance</h2>
 *
 * <p>Tests AC1, AC2, AC3, and AC5 are RED-first: they fail before the production code fix because
 * {@code DefaultDraftService.apply()} does not yet create any TeamAvatars. The RED state was
 * verified before the GREEN production-code commit.
 *
 * @see DraftService
 * @see de.vvwt.tm.tournament.internal.DefaultDraftService
 * @see <a href="E51S02">E51S02 — Avatar persistence at DraftConfig-Apply (DEC-55 D-1)</a>
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-46">DEC-46 — DEC-26 scope extension</a>
 * @see <a href="DEC-55">DEC-55 D-1 — Avatar-Erzeugung-Zeitpunkt verschoben auf
 *     DraftConfig-Apply</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:draftapplyavatarsit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("DefaultDraftService apply() — avatar persistence at apply-time IT — E51S02 RED-first")
class DefaultDraftServiceApplyAvatarsIT {

    /** Subject: inject via public interface per DEC-36. */
    @Autowired private DraftService draftService;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private DataSource dataSource;

    private AssertDbConnection assertDb;

    private UUID locationId;

    // Scenario A: 6 participating teams, 2-group Phase 1 + roundRobin Phase 2 + siegerehrung
    private UUID tournamentA;
    private List<UUID> participatingTeamIdsA;

    // Scenario B: 8 total teams (6 participate=true, 2 participate=false)
    private UUID tournamentB;

    @BeforeEach
    void setUp() {
        assertDb = AssertDbConnectionFactory.of(dataSource).create();
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "AvatarIT Location");

        // --- Tournament A: 6 participating teams ---
        tournamentA = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentA,
                locationId,
                "Avatar IT Tournament A",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                6);

        participatingTeamIdsA = insertParticipatingTeams(tournamentA, 6);

        // --- Tournament B: 6 participating + 2 non-participating ---
        tournamentB = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentB,
                locationId,
                "Avatar IT Tournament B",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                8);

        insertParticipatingTeams(tournamentB, 6);
        insertNonParticipatingTeams(tournamentB, 2, 7);
    }

    @AfterEach
    void tearDown() {
        for (UUID tid : List.of(tournamentA, tournamentB)) {
            // Delete match rows before phase (FK ON DELETE RESTRICT: match → phase).
            // Async MatchGenJobListener may have inserted match rows after apply() returned.
            jdbcTemplate.update(
                    "DELETE FROM match WHERE phase_id IN"
                            + " (SELECT id FROM phase WHERE tournament_id = ?)",
                    tid);
            jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tid);
            jdbcTemplate.update(
                    "DELETE FROM phase_breaks WHERE phase_id IN"
                            + " (SELECT id FROM phase WHERE tournament_id = ?)",
                    tid);
            jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tid);
            jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tid);
            jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tid);
        }
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // =========================================================================
    // AC-TEST-DRAFT-APPLY-PERSISTS-PHASE-1-AVATARS-RED (AC1)
    // =========================================================================

    /**
     * RED-first: given a tournament with 6 participating teams and a 2-group Phase 1, when {@code
     * apply()} is called, exactly 6 TeamAvatar records are persisted for Phase 1 with structural
     * identity {@code (phaseId, groupNumber, groupPosition)} populated AND {@code teamId} populated
     * from the participating team's UUID.
     *
     * <p>Test fails BEFORE the fix (0 avatars currently created by apply()).
     */
    @Test
    @DisplayName(
            "apply() persists exactly 6 avatars for Phase 1 with teamId populated"
                    + " (AC-TEST-DRAFT-APPLY-PERSISTS-PHASE-1-AVATARS-RED)")
    void apply_withSixParticipatingTeams_persistsSixPhase1AvatarsWithTeamId() {
        DraftConfig config = twoPhaseConfig(2);

        List<UUID> phaseIds = draftService.apply(tournamentA, config);

        assertThat(phaseIds).as("apply() must create 2 phase records").hasSize(2);

        UUID phase1Id = phaseIds.get(0);

        // DEC-26 Rule 2: verify Phase 1 avatar count via assertj-db
        Table avatarTable = assertDb.table("team_avatar").build();
        // Total avatars must be at least 6 (for Phase 1)
        // We'll query by phase_id to be precise
        Integer phase1AvatarCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                        Integer.class,
                        phase1Id);
        assertThat(phase1AvatarCount)
                .as("Phase 1 must have exactly 6 TeamAvatar records (one per participating team)")
                .isEqualTo(6);

        // Verify all Phase 1 avatars have teamId populated (not null)
        Integer phase1AvatarsWithNullTeamId =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ? AND team_id IS NULL",
                        Integer.class,
                        phase1Id);
        assertThat(phase1AvatarsWithNullTeamId)
                .as("Phase 1 avatars must have teamId populated (not null)")
                .isEqualTo(0);

        // Verify all Phase 1 avatar teamIds are from the participating team set
        List<UUID> avatarTeamIds =
                jdbcTemplate.queryForList(
                        "SELECT team_id FROM team_avatar WHERE phase_id = ?", UUID.class, phase1Id);
        assertThat(avatarTeamIds)
                .as("Phase 1 avatar teamIds must match the participating teams")
                .containsExactlyInAnyOrderElementsOf(participatingTeamIdsA);

        // Verify structural identity fields are populated (DEC-9)
        Integer missingStructuralIdentity =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?"
                                + " AND (group_number IS NULL OR group_position IS NULL"
                                + " OR group_number = 0 OR group_position = 0)",
                        Integer.class,
                        phase1Id);
        assertThat(missingStructuralIdentity)
                .as("All Phase 1 avatars must have groupNumber and groupPosition populated (DEC-9)")
                .isEqualTo(0);
    }

    // =========================================================================
    // AC-TEST-DRAFT-APPLY-PERSISTS-PHASE-2-PLUS-STRUCTURAL-AVATARS-RED (AC2)
    // =========================================================================

    /**
     * RED-first: given a 3-phase config (Phase 1: 2 groups, Phase 2: 2 groups roundRobin, Phase 3:
     * siegerehrung), when {@code apply()} is called with 6 participating teams, Phase 2 has the
     * structurally-required number of avatars with {@code teamId IS NULL}.
     *
     * <p>Test fails BEFORE the fix (no Phase 2 avatars currently created).
     */
    @Test
    @DisplayName(
            "apply() persists structural avatars for Phase 2 with teamId=null"
                    + " (AC-TEST-DRAFT-APPLY-PERSISTS-PHASE-2-PLUS-STRUCTURAL-AVATARS-RED)")
    void apply_withThreePhaseConfig_persistsPhase2StructuralAvatarsWithNullTeamId() {
        // 3-phase: Phase 1 (roundRobin, 2 groups), Phase 2 (roundRobin, 2 groups), Phase 3
        // (siegerehrung)
        DraftConfig config = threePhaseConfig(2, 2);

        List<UUID> phaseIds = draftService.apply(tournamentA, config);

        assertThat(phaseIds).as("apply() must create 3 phase records").hasSize(3);

        UUID phase2Id = phaseIds.get(1);

        // Phase 2 structural shape: 6 participating teams / 2 groups = 3 positions per group
        // Total Phase 2 avatars = 2 groups * 3 positions = 6
        Integer phase2AvatarCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                        Integer.class,
                        phase2Id);
        assertThat(phase2AvatarCount)
                .as(
                        "Phase 2 must have exactly 6 structural avatar records"
                                + " (groupCount=2 * positionsPerGroup=3)")
                .isEqualTo(6);

        // Verify all Phase 2 avatars have teamId IS NULL (structural placeholder — DEC-55 D-1)
        Integer phase2AvatarsWithNonNullTeamId =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ? AND team_id IS NOT"
                                + " NULL",
                        Integer.class,
                        phase2Id);
        assertThat(phase2AvatarsWithNonNullTeamId)
                .as("Phase 2 avatars must have teamId=null (structural placeholder)")
                .isEqualTo(0);
    }

    // =========================================================================
    // AC-TEST-DRAFT-APPLY-DEC-9-IDENTITY-INVARIANT-RED (AC3)
    // =========================================================================

    /**
     * RED-first: given any apply, the persisted avatars have unique {@code (phaseId, groupNumber,
     * groupPosition)} triples within each phase — DEC-9 structural-identity invariant.
     *
     * <p>Test fails BEFORE the fix (no avatars created, so invariant vacuously fails by absence of
     * expected data).
     */
    @Test
    @DisplayName(
            "apply() produces unique (phaseId,groupNumber,groupPosition) triples"
                    + " (AC-TEST-DRAFT-APPLY-DEC-9-IDENTITY-INVARIANT-RED)")
    void apply_producesUniqueStructuralIdentityTriples() {
        DraftConfig config = twoPhaseConfig(2);

        List<UUID> phaseIds = draftService.apply(tournamentA, config);

        UUID phase1Id = phaseIds.get(0);

        // Count total avatars for Phase 1
        Integer totalAvatars =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                        Integer.class,
                        phase1Id);

        // Count distinct (groupNumber, groupPosition) combinations for Phase 1
        Integer distinctTriples =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ("
                                + "SELECT DISTINCT group_number, group_position"
                                + " FROM team_avatar WHERE phase_id = ?"
                                + ") sub",
                        Integer.class,
                        phase1Id);

        assertThat(totalAvatars)
                .as("Phase 1 must have 6 avatars for 6 participating teams")
                .isEqualTo(6);

        assertThat(distinctTriples)
                .as(
                        "All (groupNumber, groupPosition) triples must be unique within Phase 1"
                                + " (DEC-9 structural-identity invariant)")
                .isEqualTo(totalAvatars);
    }

    // =========================================================================
    // AC-TEST-DRAFT-APPLY-IDEMPOTENT-GREEN (AC4) — regression after GREEN fix
    // =========================================================================

    /**
     * Idempotency regression: re-applying the same DraftConfig produces the same set of avatars (no
     * duplicates, no orphan rows).
     *
     * <p>This test is not RED-first — it validates idempotency semantics introduced by the fix.
     */
    @Test
    @DisplayName(
            "apply() is idempotent — re-applying same config produces same avatar count"
                    + " (AC-TEST-DRAFT-APPLY-IDEMPOTENT-GREEN)")
    void apply_calledTwice_producesIdempotentAvatarSet() {
        // The tournament must be reset to DRAFT status between calls — but apply() transitions to
        // PLANNED. We use tournamentB (also in DRAFT) for the second call scenario by resetting:
        // Actually apply() leaves the tournament in PLANNED state, so we can't call apply() twice
        // on the same tournament. Idempotency is verified at the structural level within the SAME
        // apply() call: we assert that a single apply produces the expected count, and that a
        // reset-plan + re-apply produces the same count.
        // Approach: apply() on tournamentA, count avatars; resetPlan(), re-apply, count again.
        DraftConfig config = twoPhaseConfig(2);

        List<UUID> firstPhaseIds = draftService.apply(tournamentA, config);
        UUID firstPhase1Id = firstPhaseIds.get(0);

        Integer avatarsAfterFirstApply =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                        Integer.class,
                        firstPhase1Id);

        assertThat(avatarsAfterFirstApply)
                .as("First apply must create 6 avatars for Phase 1")
                .isEqualTo(6);

        // Reset plan (back to DRAFT) and re-apply
        draftService.resetPlan(tournamentA);

        List<UUID> secondPhaseIds = draftService.apply(tournamentA, config);
        UUID secondPhase1Id = secondPhaseIds.get(0);

        Integer avatarsAfterSecondApply =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                        Integer.class,
                        secondPhase1Id);

        assertThat(avatarsAfterSecondApply)
                .as("Second apply must produce same avatar count as first apply (idempotent)")
                .isEqualTo(avatarsAfterFirstApply);

        // Verify no orphan avatars from previous apply remain
        Integer totalAvatarsForTournament =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE tournament_id = ?",
                        Integer.class,
                        tournamentA);
        assertThat(totalAvatarsForTournament)
                .as(
                        "Re-apply must not leave orphan avatars from the previous apply"
                                + " (delete-and-recreate semantics)")
                .isEqualTo(avatarsAfterSecondApply);
    }

    // =========================================================================
    // AC-TEST-DRAFT-APPLY-NON-PARTICIPATING-EXCLUDED-PHASE-1-RED (AC5)
    // =========================================================================

    /**
     * RED-first: given a tournament with 8 total teams (6 participate=true, 2 participate=false),
     * when {@code apply()} runs, Phase 1 has exactly 6 avatars (the 2 non-participating teams
     * produce no Phase 1 avatar).
     *
     * <p>Test fails BEFORE the fix (0 avatars created).
     */
    @Test
    @DisplayName(
            "apply() excludes non-participating teams from Phase 1 avatars"
                    + " (AC-TEST-DRAFT-APPLY-NON-PARTICIPATING-EXCLUDED-PHASE-1-RED)")
    void apply_withMixedParticipation_excludesNonParticipatingFromPhase1() {
        DraftConfig config = twoPhaseConfig(2);

        List<UUID> phaseIds = draftService.apply(tournamentB, config);

        UUID phase1Id = phaseIds.get(0);

        Integer phase1AvatarCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                        Integer.class,
                        phase1Id);
        assertThat(phase1AvatarCount)
                .as(
                        "Phase 1 must have exactly 6 avatars (only participating teams),"
                                + " not 8 (all teams)")
                .isEqualTo(6);

        // Verify none of the non-participating team UUIDs appear as teamId
        List<UUID> nonParticipatingTeamIds =
                jdbcTemplate.queryForList(
                        "SELECT id FROM team WHERE tournament_id = ? AND participate = FALSE",
                        UUID.class,
                        tournamentB);
        assertThat(nonParticipatingTeamIds)
                .as("Fixture must have 2 non-participating teams")
                .hasSize(2);

        for (UUID nonParticipatingId : nonParticipatingTeamIds) {
            Integer countForNonParticipating =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ? AND team_id = ?",
                            Integer.class,
                            phase1Id,
                            nonParticipatingId);
            assertThat(countForNonParticipating)
                    .as("Non-participating team must NOT have a Phase 1 avatar")
                    .isEqualTo(0);
        }
    }

    // =========================================================================
    // AC-ERROR-HANDLING-EMPTY-PARTICIPATING-TEAMS
    // =========================================================================

    /**
     * Error handling: given a tournament with zero participating teams, apply() rejects before any
     * avatar or phase is persisted.
     *
     * <p>Not RED-first — validates the error-handling extension introduced by the fix.
     */
    @Test
    @DisplayName(
            "apply() rejects with error when no participating teams exist"
                    + " (AC-ERROR-HANDLING-EMPTY-PARTICIPATING-TEAMS)")
    void apply_withNoParticipatingTeams_rejectsBeforeAnyPersistence() {
        // Create a tournament with ONLY non-participating teams
        UUID tournamentEmpty = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentEmpty,
                locationId,
                "Empty Participation Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                2);
        insertNonParticipatingTeams(tournamentEmpty, 2, 1);

        DraftConfig config = twoPhaseConfig(1);

        try {
            assertThatThrownBy(() -> draftService.apply(tournamentEmpty, config))
                    .as("apply() must throw when no participating teams exist")
                    .isInstanceOf(Exception.class);

            // Verify no phases or avatars were persisted
            // (AC-ERROR-HANDLING-EMPTY-PARTICIPATING-TEAMS)
            Integer phaseCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM phase WHERE tournament_id = ?",
                            Integer.class,
                            tournamentEmpty);
            assertThat(phaseCount)
                    .as("No phases must be persisted when apply() rejects on empty participation")
                    .isEqualTo(0);
        } finally {
            // Clean up in FK-safe order
            jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentEmpty);
            jdbcTemplate.update(
                    "DELETE FROM phase_breaks WHERE phase_id IN"
                            + " (SELECT id FROM phase WHERE tournament_id = ?)",
                    tournamentEmpty);
            jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentEmpty);
            jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentEmpty);
            jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentEmpty);
        }
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    /**
     * Inserts {@code count} participating teams for the given tournament, numbered 1..count.
     *
     * @param tournamentId the tournament UUID
     * @param count number of teams to insert
     * @return list of inserted team UUIDs in teamNumber order
     */
    private List<UUID> insertParticipatingTeams(UUID tournamentId, int count) {
        List<UUID> ids = new java.util.ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            UUID teamId = UUID.randomUUID();
            ids.add(teamId);
            jdbcTemplate.update(
                    "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    teamId,
                    tournamentId,
                    i,
                    "Team " + i,
                    true,
                    LocalDateTime.now());
        }
        return java.util.Collections.unmodifiableList(ids);
    }

    /**
     * Inserts {@code count} non-participating teams for the given tournament, starting from team
     * number {@code startNumber}.
     *
     * @param tournamentId the tournament UUID
     * @param count number of teams to insert
     * @param startNumber first team number
     */
    private void insertNonParticipatingTeams(UUID tournamentId, int count, int startNumber) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    tournamentId,
                    startNumber + i,
                    "NonParticipating Team " + (startNumber + i),
                    false,
                    LocalDateTime.now());
        }
    }

    /**
     * 2-phase DraftConfig: Phase 1 (roundRobin, groupCount groups) + Phase 2 (siegerehrung, 1
     * group).
     *
     * @param groupCount number of groups for Phase 1
     * @return the config
     */
    private static DraftConfig twoPhaseConfig(int groupCount) {
        return new DraftConfig(
                List.of(
                        new DraftSection(
                                1, "team_number", groupCount, "roundRobin", 0, 0, 15, 1, List.of()),
                        new DraftSection(
                                2, "team_number", 1, "siegerehrung", 0, 0, 15, 1, List.of())));
    }

    /**
     * 3-phase DraftConfig: Phase 1 (roundRobin, phase1Groups), Phase 2 (roundRobin, phase2Groups),
     * Phase 3 (siegerehrung, 1 group).
     *
     * @param phase1Groups number of groups for Phase 1
     * @param phase2Groups number of groups for Phase 2
     * @return the config
     */
    private static DraftConfig threePhaseConfig(int phase1Groups, int phase2Groups) {
        return new DraftConfig(
                List.of(
                        new DraftSection(
                                1,
                                "team_number",
                                phase1Groups,
                                "roundRobin",
                                0,
                                0,
                                15,
                                1,
                                List.of()),
                        new DraftSection(
                                2,
                                "team_number",
                                phase2Groups,
                                "roundRobin",
                                0,
                                0,
                                15,
                                1,
                                List.of()),
                        new DraftSection(
                                3, "team_number", 1, "siegerehrung", 0, 0, 15, 1, List.of())));
    }
}
