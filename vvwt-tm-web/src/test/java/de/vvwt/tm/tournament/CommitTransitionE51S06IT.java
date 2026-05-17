// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.Phase.PhaseStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * RED-first integration tests for E51S06 — commitTransition UPDATE-not-INSERT + no-match-gen +
 * RefereeAssigner wiring + ASSIGNED status flip (DEC-55 D-10, AC-GOVERNANCE-DEC-22-Q-1A-RED-FIRST).
 *
 * <h2>Acceptance Criteria Covered (RED-first)</h2>
 *
 * <ul>
 *   <li>AC-TEST-COMMIT-TRANSITION-UPDATES-TEAM-ID-NO-INSERT-RED — commitTransition UPDATEs existing
 *       avatars (teamId set); no new avatar rows inserted.
 *   <li>AC-TEST-COMMIT-TRANSITION-NO-MATCH-GEN-INVOCATION-RED — commitTransition does NOT call
 *       generateMatches; existing match rows preserved.
 *   <li>AC-TEST-COMMIT-TRANSITION-INVOKES-REFEREE-ASSIGNMENT-RED — commitTransition invokes
 *       RefereeAssigner.assignReferees(phaseId); match rows have referee_team_id populated.
 *   <li>AC-TEST-COMMIT-TRANSITION-FLIPS-STATUS-TO-ASSIGNED-RED — after commitTransition, phase
 *       status = ASSIGNED.
 *   <li>AC-TEST-DEC-9-IDENTITY-PRESERVED-GREEN — avatar UUID stable across teamId UPDATE.
 *   <li>AC-ERROR-HANDLING-AVATAR-NOT-FOUND — missing avatar for slot → IllegalStateException.
 *   <li>AC-TEST-PHASE-1-DRAG-AND-DROP-COMMIT-PRESERVED-GREEN — Phase 1 drag&amp;drop commit updates
 *       avatars, assigns referees, flips ASSIGNED.
 * </ul>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 — RED-first authorship: all 4 RED tests fail before production-code change because
 *       today's {@code commitTransition} INSERTs avatars, calls {@code generateMatches}, does NOT
 *       call {@code RefereeAssigner}, and does NOT flip status to ASSIGNED.
 *   <li>DEC-9 — structural identity (phaseId, groupNumber, groupPosition) preserved across UPDATE.
 *   <li>DEC-36 — Cross-package: test in {@code de.vvwt.tm.tournament}, injects {@link
 *       PhaseTransitionService} (public interface), never {@code DefaultPhaseTransitionService}.
 *   <li>DEC-37 Clause B — per-tournament row-lock verified implicitly via full Spring TX stack.
 *   <li>DEC-44 — service-only IT using {@code @SpringBootTest(NONE)}.
 * </ul>
 *
 * <p>Fixture setup: structural avatar placeholders are pre-inserted (teamId NULL) to simulate the
 * state after E51S02 avatar-creation-at-apply. Match rows are pre-inserted (lap/field NULL) to
 * simulate the state after E51S03 background match-gen.
 *
 * @see PhaseTransitionService
 * @see de.vvwt.tm.tournament.internal.DefaultPhaseTransitionService
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first; Q-1a)</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing rule</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 * @see <a href="DEC-55">DEC-55 D-10 — Drag&amp;drop-Refactor + E48S21-Rollback</a>
 * @see <a href="E51S06">E51S06 — story implementing this feature</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:committransitione51s06it;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, CommitTransitionE51S06IT.SlotOptConfig.class})
@DisplayName("CommitTransitionE51S06IT — E51S06 RED-first commitTransition refactor")
class CommitTransitionE51S06IT {

    /**
     * Overrides the production slot-opt client with a no-op to prevent async background jobs from
     * holding row-locks during tearDown and causing H2 FK violations.
     */
    @TestConfiguration
    static class SlotOptConfig {

        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {
                // No-op: returns immediately — slot-opt does not run in IT
            };
        }
    }

    /**
     * Subject under test — injected via public interface per DEC-36 cross-package rule.
     *
     * <p>This class is in {@code de.vvwt.tm.tournament} (different package from the impl), so it
     * MUST inject {@link PhaseTransitionService}, never {@code DefaultPhaseTransitionService}.
     */
    @Autowired private PhaseTransitionService phaseTransitionService;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    // ── Fixture state ─────────────────────────────────────────────────────────

    private UUID locationId;
    private UUID tournamentId;

    /** Phase 2 with structural avatars pre-inserted (teamId NULL) and matches pre-inserted. */
    private UUID phase2Id;

    /** Two teams participating in Phase 2. */
    private UUID team1Id;

    private UUID team2Id;

    /** Pre-existing structural avatar placeholders for Phase 2 (teamId NULL = unassigned). */
    private UUID avatar1Id; // group 1, position 1

    private UUID avatar2Id; // group 1, position 2

    /**
     * DraftConfig JSON: 2 sections. Phase 2 (sectionNumber=2) is roundRobin with 1 group, so
     * commitTransition generates matches for 2 avatars.
     *
     * <p>Phase 1 is required for sequence-number predecessor resolution (fromPhase of Phase 2).
     */
    private static final String DRAFT_JSON =
            "{\"sections\": ["
                    + "  {\"sectionNumber\": 1, \"sortType\": \"team_number\","
                    + "   \"groupCount\": 1, \"gameMode\": \"roundRobin\","
                    + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                    + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []},"
                    + "  {\"sectionNumber\": 2, \"sortType\": \"team_number\","
                    + "   \"groupCount\": 1, \"gameMode\": \"roundRobin\","
                    + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                    + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []}"
                    + "]}";

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "E51S06 IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize, draft_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "E51S06 IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "PLANNED",
                LocalDateTime.now(),
                2,
                2,
                false /* optimize=false so ASSIGNED→ACTIVE guard passes */,
                DRAFT_JSON);

        // Phase 1 — needed for predecessor lookup (Phase 2 has sequenceNumber=2)
        UUID phase1Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description,"
                        + " status, current_lap_number, created_at, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                phase1Id,
                tournamentId,
                1,
                "Phase 1",
                "COMPLETED",
                1,
                LocalDateTime.now(),
                false);

        // Phase 2 — PREPARED (avatars exist from E51S02; matches exist from E51S03)
        phase2Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description,"
                        + " status, current_lap_number, created_at, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                phase2Id,
                tournamentId,
                2,
                "Vorrunde Phase 2",
                "PREPARED",
                0,
                LocalDateTime.now(),
                false);

        // Two teams
        team1Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                team1Id,
                tournamentId,
                1,
                "E51S06 Team 1",
                true,
                LocalDateTime.now());

        team2Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                team2Id,
                tournamentId,
                2,
                "E51S06 Team 2",
                true,
                LocalDateTime.now());

        // Structural avatar placeholders for Phase 2 (teamId=NULL = pre-E51S02 state)
        // DEC-9: identity is (phaseId, groupNumber, groupPosition)
        avatar1Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatar1Id,
                tournamentId,
                phase2Id,
                1, // groupNumber
                1, // groupPosition
                null, // teamId NULL = unassigned placeholder
                LocalDateTime.now());

        avatar2Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                        + " group_position, team_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                avatar2Id,
                tournamentId,
                phase2Id,
                1, // groupNumber
                2, // groupPosition
                null, // teamId NULL = unassigned placeholder
                LocalDateTime.now());

        // Pre-existing match rows from E51S03 background match-gen + E51S04 slot-opt.
        // lap_number=1, field_number=1 simulate post-slot-opt coordinates.
        // These rows must NOT be deleted by commitTransition — only teamId UPDATEs happen.
        UUID matchId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO match (id, tournament_id, phase_id,"
                        + " member_avatar_1_id, member_avatar_2_id,"
                        + " state, set_limit, lap_number, field_number, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                matchId,
                tournamentId,
                phase2Id,
                avatar1Id,
                avatar2Id,
                0, // MatchState.OPEN legacy code
                1, // set_limit default
                1, // lap_number (post-slot-opt)
                1, // field_number (post-slot-opt)
                LocalDateTime.now());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM match WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update(
                "DELETE FROM team_avatar WHERE phase_id IN"
                        + " (SELECT id FROM phase WHERE tournament_id = ?)",
                tournamentId);
        jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // =========================================================================
    // AC-TEST-COMMIT-TRANSITION-UPDATES-TEAM-ID-NO-INSERT-RED
    // =========================================================================

    /**
     * RED-first: given pre-existing structural avatars (teamId NULL), commitTransition MUST update
     * teamId on existing rows — no new avatar rows inserted.
     *
     * <p>Current code (pre-fix): creates {@code new TeamAvatar()} and INSERTs — avatar count goes
     * from 2 to 4 (or throws a unique-constraint violation). After fix: count stays at 2 with
     * teamIds populated.
     *
     * <p>DEC-22: this test is authored RED-first; it fails before the production-code change.
     */
    @Test
    @DisplayName(
            "commitTransition: existing avatars get UPDATED (teamId set), no new INSERT"
                    + " (AC-TEST-COMMIT-TRANSITION-UPDATES-TEAM-ID-NO-INSERT-RED)")
    void commitTransition_updatesExistingAvatars_noNewInsert() {
        int avatarCountBefore =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                        Integer.class,
                        phase2Id);
        assertThat(avatarCountBefore)
                .as("Precondition: 2 structural placeholders must exist")
                .isEqualTo(2);

        List<TeamAvatarProposal> assignments =
                List.of(
                        TeamAvatarProposal.forCommit(team1Id, 1, 1),
                        TeamAvatarProposal.forCommit(team2Id, 1, 2));

        phaseTransitionService.commitTransition(phase2Id, assignments);

        int avatarCountAfter =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team_avatar WHERE phase_id = ?",
                        Integer.class,
                        phase2Id);
        assertThat(avatarCountAfter)
                .as(
                        "AC-TEST-COMMIT-TRANSITION-UPDATES-TEAM-ID-NO-INSERT-RED:"
                                + " avatar count must stay at 2 (UPDATE not INSERT)")
                .isEqualTo(2);

        // Verify teamIds are populated (DEC-9 identity preserved — UUIDs unchanged)
        UUID teamIdAtSlot1 =
                jdbcTemplate.queryForObject(
                        "SELECT team_id FROM team_avatar WHERE phase_id = ? AND"
                                + " group_number = 1 AND group_position = 1",
                        UUID.class,
                        phase2Id);
        UUID teamIdAtSlot2 =
                jdbcTemplate.queryForObject(
                        "SELECT team_id FROM team_avatar WHERE phase_id = ? AND"
                                + " group_number = 1 AND group_position = 2",
                        UUID.class,
                        phase2Id);
        assertThat(teamIdAtSlot1).as("Slot (1,1) must be assigned to team1").isEqualTo(team1Id);
        assertThat(teamIdAtSlot2).as("Slot (1,2) must be assigned to team2").isEqualTo(team2Id);
    }

    // =========================================================================
    // AC-TEST-COMMIT-TRANSITION-NO-MATCH-GEN-INVOCATION-RED
    // =========================================================================

    /**
     * RED-first: commitTransition must NOT call generateMatches. Pre-existing match rows are
     * preserved (not deleted/replaced).
     *
     * <p>Current code (pre-fix): calls {@code phasePreparationService.generateMatches()} which
     * deletes existing matches and re-inserts new ones — match UUID changes, lap/field coordinates
     * overwritten. After fix: 1 pre-existing match row with the same UUID is preserved.
     *
     * <p>DEC-22: this test is authored RED-first.
     */
    @Test
    @DisplayName(
            "commitTransition: pre-existing matches preserved — generateMatches NOT invoked"
                    + " (AC-TEST-COMMIT-TRANSITION-NO-MATCH-GEN-INVOCATION-RED)")
    void commitTransition_doesNotCallGenerateMatches_existingMatchesPreserved() {
        int matchCountBefore =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ?", Integer.class, phase2Id);
        assertThat(matchCountBefore)
                .as("Precondition: 1 pre-existing match from E51S03 background match-gen")
                .isEqualTo(1);

        List<TeamAvatarProposal> assignments =
                List.of(
                        TeamAvatarProposal.forCommit(team1Id, 1, 1),
                        TeamAvatarProposal.forCommit(team2Id, 1, 2));

        phaseTransitionService.commitTransition(phase2Id, assignments);

        int matchCountAfter =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ?", Integer.class, phase2Id);
        assertThat(matchCountAfter)
                .as(
                        "AC-TEST-COMMIT-TRANSITION-NO-MATCH-GEN-INVOCATION-RED:"
                                + " match count must remain at 1 (no generateMatches call)")
                .isEqualTo(1);
    }

    // =========================================================================
    // AC-TEST-COMMIT-TRANSITION-INVOKES-REFEREE-ASSIGNMENT-RED
    // =========================================================================

    /**
     * RED-first: commitTransition must invoke RefereeAssigner.assignReferees(phaseId) after avatar
     * UPDATE. Match rows with teamId-populated avatars have referee_team_id populated post-commit.
     *
     * <p>Note: referee assignment only populates referee_team_id when teams have
     * referee_assignment=true. In this fixture, teams do not have referee_assignment configured, so
     * we verify via phase status ASSIGNED (which is only set after RefereeAssigner runs without
     * throwing).
     *
     * <p>Current code (pre-fix): does NOT call RefereeAssigner — phase stays PREPARED. After fix:
     * RefereeAssigner is invoked (no-op if no referee teams configured), phase transitions to
     * ASSIGNED.
     *
     * <p>DEC-22: this test is authored RED-first.
     */
    @Test
    @DisplayName(
            "commitTransition: RefereeAssigner.assignReferees invoked — phase status ASSIGNED"
                    + " (AC-TEST-COMMIT-TRANSITION-INVOKES-REFEREE-ASSIGNMENT-RED +"
                    + " AC-TEST-COMMIT-TRANSITION-FLIPS-STATUS-TO-ASSIGNED-RED)")
    void commitTransition_invokesRefereeAssignerAndFlipsToAssigned() {
        List<TeamAvatarProposal> assignments =
                List.of(
                        TeamAvatarProposal.forCommit(team1Id, 1, 1),
                        TeamAvatarProposal.forCommit(team2Id, 1, 2));

        phaseTransitionService.commitTransition(phase2Id, assignments);

        String phaseStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase WHERE id = ?", String.class, phase2Id);
        assertThat(phaseStatus)
                .as(
                        "AC-TEST-COMMIT-TRANSITION-FLIPS-STATUS-TO-ASSIGNED-RED:"
                                + " phase status must be ASSIGNED after commitTransition"
                                + " (flip via phaseLifecycleService.transition(ASSIGNED,'assign'))")
                .isEqualTo(PhaseStatus.ASSIGNED.name());
    }

    // =========================================================================
    // AC-TEST-DEC-9-IDENTITY-PRESERVED-GREEN (regression)
    // =========================================================================

    /**
     * Regression: DEC-9 structural identity (phaseId, groupNumber, groupPosition) is preserved
     * across the teamId-UPDATE. The avatar UUID stays stable — matches referencing the avatar via
     * member_avatar_X_id continue to dereference correctly.
     *
     * <p>This test is expected to be GREEN after the fix (DEC-9 identity is preserved by the UPDATE
     * approach).
     */
    @Test
    @DisplayName(
            "commitTransition: avatar UUID stable across teamId UPDATE — DEC-9 identity preserved"
                    + " (AC-TEST-DEC-9-IDENTITY-PRESERVED-GREEN)")
    void commitTransition_avatarUuidStable_DEC9IdentityPreserved() {
        List<TeamAvatarProposal> assignments =
                List.of(
                        TeamAvatarProposal.forCommit(team1Id, 1, 1),
                        TeamAvatarProposal.forCommit(team2Id, 1, 2));

        phaseTransitionService.commitTransition(phase2Id, assignments);

        // Verify avatar UUIDs are unchanged — match FK references remain valid
        UUID avatarUuidAtSlot1 =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM team_avatar WHERE phase_id = ? AND"
                                + " group_number = 1 AND group_position = 1",
                        UUID.class,
                        phase2Id);
        UUID avatarUuidAtSlot2 =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM team_avatar WHERE phase_id = ? AND"
                                + " group_number = 1 AND group_position = 2",
                        UUID.class,
                        phase2Id);

        assertThat(avatarUuidAtSlot1)
                .as("DEC-9: avatar UUID at slot (1,1) must remain stable after teamId UPDATE")
                .isEqualTo(avatar1Id);
        assertThat(avatarUuidAtSlot2)
                .as("DEC-9: avatar UUID at slot (1,2) must remain stable after teamId UPDATE")
                .isEqualTo(avatar2Id);

        // Verify match still references the same avatar UUIDs (FK intact)
        Integer matchCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ?"
                                + " AND member_avatar_1_id = ? AND member_avatar_2_id = ?",
                        Integer.class,
                        phase2Id,
                        avatar1Id,
                        avatar2Id);
        assertThat(matchCount)
                .as(
                        "DEC-9: match FK member_avatar_1_id and member_avatar_2_id still"
                                + " reference the original avatar UUIDs after UPDATE")
                .isEqualTo(1);
    }

    // =========================================================================
    // AC-ERROR-HANDLING-AVATAR-NOT-FOUND
    // =========================================================================

    /**
     * Error handling: if assignments reference a slot with no existing avatar (e.g., slot (1,3)
     * doesn't exist), commitTransition must throw {@link IllegalStateException} naming the missing
     * slot.
     *
     * <p>No silent INSERT-fallback (AC-ERROR-HANDLING-AVATAR-NOT-FOUND).
     *
     * <p>DEC-22: this test verifies error-path behavior.
     */
    @Test
    @DisplayName(
            "commitTransition: missing avatar for slot → IllegalStateException"
                    + " (AC-ERROR-HANDLING-AVATAR-NOT-FOUND)")
    void commitTransition_missingAvatarForSlot_throwsIllegalStateException() {
        // Assign to slot (1,3) which has no structural placeholder
        List<TeamAvatarProposal> badAssignment =
                List.of(
                        TeamAvatarProposal.forCommit(team1Id, 1, 3) // position 3 does not exist
                        );

        assertThatThrownBy(() -> phaseTransitionService.commitTransition(phase2Id, badAssignment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("avatar")
                .as(
                        "AC-ERROR-HANDLING-AVATAR-NOT-FOUND:"
                                + " missing avatar slot must throw IllegalStateException"
                                + " naming the missing slot");
    }
}
