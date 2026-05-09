package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.exceptions.ConflictException;
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
 * RED-first integration tests for DEC-59 Clause C precondition checks in {@link
 * PhaseTransitionService#commitTransition(UUID, List)} (E51S18).
 *
 * <h2>Acceptance Criteria Covered (RED-first)</h2>
 *
 * <ul>
 *   <li>AC-ERROR-OPERATOR-CONFIRMATION-PRECONDITION-PHASE-NOT-PREPARED: commitTransition() on a
 *       PENDING phase → ConflictException (HTTP 409). Pre-fix: {@code IllegalStateException} (500)
 *       via transition-table. Post-fix: explicit {@code ConflictException} precondition check.
 *   <li>AC-ERROR-OPERATOR-CONFIRMATION-PRECONDITION-PREVIOUS-PHASE-NOT-COMPLETED:
 *       commitTransition() on Phase 2 when Phase 1 is ACTIVE (not COMPLETED) → ConflictException
 *       (HTTP 409). Pre-fix: no check, succeeds or throws from another path. Post-fix: explicit
 *       {@code ConflictException}.
 * </ul>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 — RED-first authorship (Q-1a Pattern B): these tests are authored RED-first. They
 *       fail before the E51S18 production-code change in {@code DefaultPhaseTransitionService}.
 *   <li>DEC-26/DEC-46 — Three-rule: schema from Flyway, assertj assertions, direct JDBC fixtures.
 *   <li>DEC-36 — Cross-package: test injects {@link PhaseTransitionService} (public interface).
 *   <li>DEC-37 Clause B — per-tournament row-lock verified via full Spring TX stack.
 *   <li>DEC-44 — service-only IT ({@code webEnvironment=NONE}).
 *   <li>DEC-59 Clause C — operator-confirmation: confirmation is the SOLE trigger for teamId
 *       population; preconditions guard the confirmation gate.
 * </ul>
 *
 * @see PhaseTransitionService
 * @see de.vvwt.tm.tournament.internal.DefaultPhaseTransitionService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first; Q-1a)</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing rule</a>
 * @see <a href="DEC-37">DEC-37 Clause B — per-tournament pessimistic DB row-lock</a>
 * @see <a href="DEC-44">DEC-44 — service-only IT with webEnvironment=NONE</a>
 * @see <a href="DEC-46">DEC-46 — DEC-26 scope extension to all vvwt-prj modules</a>
 * @see <a href="DEC-59">DEC-59 Clause C — operator-confirmation preconditions</a>
 * @see <a href="E51S18">E51S18 — operationalize DEC-59</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:commitpreconditionit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, CommitTransitionPreconditionE51S18IT.SlotOptConfig.class})
@DisplayName("CommitTransitionPreconditionE51S18IT — DEC-59 Clause C precondition checks")
class CommitTransitionPreconditionE51S18IT {

    /**
     * Overrides the production slot-opt client with a no-op to prevent async background jobs from
     * holding row-locks during tearDown.
     */
    @TestConfiguration
    static class SlotOptConfig {

        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {
                // No-op: slot-opt does not run in this IT
            };
        }
    }

    /** Subject under test — injected via public interface per DEC-36 cross-package rule. */
    @Autowired private PhaseTransitionService phaseTransitionService;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    // ── Fixture state ─────────────────────────────────────────────────────────

    private UUID locationId;
    private UUID tournamentId;
    private UUID phase1Id;
    private UUID phase2Id;
    private UUID team1Id;
    private UUID team2Id;
    private UUID avatar1Id;
    private UUID avatar2Id;

    /**
     * DraftConfig JSON with 2 roundRobin sections. Phase 2 used as the target of commitTransition.
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
                "CommitPrecondition IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize, draft_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "CommitPrecondition IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "PLANNED",
                LocalDateTime.now(),
                2,
                2,
                false,
                DRAFT_JSON);

        // Phase 1 — COMPLETED (default; individual tests may override via UPDATE)
        phase1Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description,"
                        + " status, current_lap_number, created_at, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                phase1Id,
                tournamentId,
                1,
                "Phase 1 (Vorrunde)",
                "COMPLETED",
                1,
                LocalDateTime.now(),
                false);

        // Phase 2 — PREPARED (default; individual tests may override via UPDATE)
        phase2Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description,"
                        + " status, current_lap_number, created_at, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                phase2Id,
                tournamentId,
                2,
                "Phase 2 (Zwischenrunde)",
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
                "CommitPrecondition Team 1",
                true,
                LocalDateTime.now());

        team2Id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                team2Id,
                tournamentId,
                2,
                "CommitPrecondition Team 2",
                true,
                LocalDateTime.now());

        // Structural avatar placeholders for Phase 2 (teamId=NULL per DEC-59 Clause B)
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
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.update(
                    "DELETE FROM match WHERE phase_id IN"
                            + " (SELECT id FROM phase WHERE tournament_id = ?)",
                    tournamentId);
            jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
        tenantBinder.unbind();
    }

    // =========================================================================
    // AC-ERROR-OPERATOR-CONFIRMATION-PRECONDITION-PHASE-NOT-PREPARED (DEC-59 Clause C)
    // =========================================================================

    /**
     * AC-ERROR-OPERATOR-CONFIRMATION-PRECONDITION-PHASE-NOT-PREPARED (E51S18, DEC-59 Clause C).
     *
     * <p>commitTransition() on a PENDING phase (not PREPARED) must throw {@link ConflictException}
     * (HTTP 409). The operator-confirmation workflow is only valid when the phase has been through
     * the match-gen pipeline and reached PREPARED status.
     *
     * <p><b>Pre-fix (RED):</b> the implicit check via {@code
     * phaseLifecycleService.transition(ASSIGNED, "assign")} throws {@code IllegalStateException}
     * (not {@code ConflictException}) — wrong HTTP status code for the operator. Post-fix: explicit
     * precondition check throws {@link ConflictException} before any teamId-UPDATE is attempted.
     *
     * @see <a href="DEC-59">DEC-59 Clause C</a>
     * @see <a href="E51S18">E51S18</a>
     */
    @Test
    @DisplayName(
            "commitTransition() on PENDING phase → ConflictException (409)"
                + " [AC-ERROR-OPERATOR-CONFIRMATION-PRECONDITION-PHASE-NOT-PREPARED, DEC-59 Clause"
                + " C]")
    void commitTransition_pendingPhase_throwsConflictException() {
        // Override phase2 to PENDING status (simulates phase that hasn't completed match-gen)
        jdbcTemplate.update("UPDATE phase SET status = 'PENDING' WHERE id = ?", phase2Id);

        List<TeamAvatarProposal> assignments =
                List.of(
                        TeamAvatarProposal.forCommit(team1Id, 1, 1),
                        TeamAvatarProposal.forCommit(team2Id, 1, 2));

        // AC-ERROR-OPERATOR-CONFIRMATION-PRECONDITION-PHASE-NOT-PREPARED:
        // Pre-fix: IllegalStateException (wrong type, wrong HTTP status).
        // Post-fix: ConflictException (correct type → HTTP 409 via GlobalExceptionHandler).
        assertThatThrownBy(() -> phaseTransitionService.commitTransition(phase2Id, assignments))
                .as(
                        "AC-ERROR-OPERATOR-CONFIRMATION-PRECONDITION-PHASE-NOT-PREPARED:"
                                + " commitTransition on PENDING phase must throw ConflictException")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("PENDING");
    }

    // =========================================================================
    // AC-ERROR-OPERATOR-CONFIRMATION-PRECONDITION-PREVIOUS-PHASE-NOT-COMPLETED (DEC-59 Clause C)
    // =========================================================================

    /**
     * AC-ERROR-OPERATOR-CONFIRMATION-PRECONDITION-PREVIOUS-PHASE-NOT-COMPLETED (E51S18, DEC-59
     * Clause C).
     *
     * <p>commitTransition() on Phase 2 when Phase 1 is ACTIVE (not COMPLETED) must throw {@link
     * ConflictException} (HTTP 409). The operator cannot confirm a phase that depends on its
     * predecessor being COMPLETED.
     *
     * <p><b>Pre-fix (RED):</b> no predecessor-check exists in {@code commitTransition()} — the call
     * proceeds and updates teamIds, then the PREPARED→ASSIGNED transition succeeds (wrong
     * behavior). Post-fix: explicit check throws {@code ConflictException} before any
     * teamId-UPDATE.
     *
     * @see <a href="DEC-59">DEC-59 Clause C</a>
     * @see <a href="E51S18">E51S18</a>
     */
    @Test
    @DisplayName(
            "commitTransition() when predecessor phase not COMPLETED → ConflictException (409)"
                    + " [AC-ERROR-OPERATOR-CONFIRMATION-PRECONDITION-PREVIOUS-PHASE-NOT-COMPLETED,"
                    + " DEC-59 Clause C]")
    void commitTransition_predecessorNotCompleted_throwsConflictException() {
        // Override Phase 1 to ACTIVE status (not COMPLETED — simulates ongoing predecessor)
        jdbcTemplate.update("UPDATE phase SET status = 'ACTIVE' WHERE id = ?", phase1Id);

        List<TeamAvatarProposal> assignments =
                List.of(
                        TeamAvatarProposal.forCommit(team1Id, 1, 1),
                        TeamAvatarProposal.forCommit(team2Id, 1, 2));

        // AC-ERROR-OPERATOR-CONFIRMATION-PRECONDITION-PREVIOUS-PHASE-NOT-COMPLETED:
        // Pre-fix: no check — commitTransition proceeds (wrong behavior).
        // Post-fix: ConflictException (predecessor must be COMPLETED) → HTTP 409.
        assertThatThrownBy(() -> phaseTransitionService.commitTransition(phase2Id, assignments))
                .as(
                        "AC-ERROR-OPERATOR-CONFIRMATION-PRECONDITION-PREVIOUS-PHASE-NOT-COMPLETED:"
                                + " commitTransition when predecessor is ACTIVE must throw"
                                + " ConflictException")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("COMPLETED");
    }
}
