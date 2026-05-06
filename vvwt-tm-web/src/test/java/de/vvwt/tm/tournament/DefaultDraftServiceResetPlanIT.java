package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.exceptions.TournamentResetPlanActiveException;
import de.vvwt.tm.tournament.exceptions.TournamentResetPlanCancelledException;
import de.vvwt.tm.tournament.exceptions.TournamentResetPlanCompletedException;
import de.vvwt.tm.tournament.exceptions.TournamentResetPlanDraftIdempotentException;
import java.time.LocalDateTime;
import java.util.UUID;
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
 * RED-first DAO IT for reset-plan operation (E48S13, AC-TEST-RESET-PLAN-DAO-IT-RED).
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>PLANNED with phases: reset-plan succeeds; phase-derived rows deleted; status = DRAFT;
 *       draftJson preserved
 *   <li>ACTIVE: rejected → {@link TournamentResetPlanActiveException} (409)
 *   <li>CANCELLED: rejected → {@link TournamentResetPlanCancelledException} (409)
 *   <li>COMPLETED: rejected → {@link TournamentResetPlanCompletedException} (409)
 *   <li>DRAFT (idempotent): rejected → {@link TournamentResetPlanDraftIdempotentException} (409)
 *       with messageKey {@code error.tournament.resetPlan.draftIdempotent}
 * </ul>
 *
 * <h2>DEC-26/DEC-46 three-rule conformance</h2>
 *
 * <ul>
 *   <li>Schema from Flyway migrations
 *   <li>Independent JDBC verifier (not service read-path)
 *   <li>Read/write decoupling via direct JDBC truth source
 * </ul>
 *
 * @see DraftService
 * @see de.vvwt.tm.tournament.internal.DefaultDraftService
 * @see <a href="E48S13">E48S13 — AC-TEST-RESET-PLAN-DAO-IT-RED</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:resetplanit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("DefaultDraftService ResetPlan IT — E48S13 RED-first")
class DefaultDraftServiceResetPlanIT {

    /** Subject: inject via public interface per DEC-36. */
    @Autowired private DraftService draftService;

    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID locationId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();
        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "ResetPlan IT Location");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM phase");
        jdbcTemplate.update("DELETE FROM team");
        jdbcTemplate.update("DELETE FROM tournament");
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // -------------------------------------------------------------------------
    // Happy-path: PLANNED with phase → status flips to DRAFT; phase deleted
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "PLANNED tournament with phase: resetPlan sets status=DRAFT and deletes phase rows")
    void resetPlan_planned_flipsStatusAndDeletesPhase() {
        String draftJson = "{\"sections\":[]}";
        UUID id = seedTournament("PLANNED", draftJson);
        UUID phaseId = seedPhase(id);

        Tournament result = draftService.resetPlan(id);

        assertThat(result.getStatus()).isEqualTo("DRAFT");

        // Independent JDBC verifier (DEC-26 Rule 2)
        String persistedStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM tournament WHERE id = ?", String.class, id);
        assertThat(persistedStatus).isEqualTo("DRAFT");

        int phaseCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase WHERE tournament_id = ?", Integer.class, id);
        assertThat(phaseCount).isZero();

        // draftJson must be preserved (AC-IMPL-RESET-PLAN-OP)
        String persistedJson =
                jdbcTemplate.queryForObject(
                        "SELECT draft_json FROM tournament WHERE id = ?", String.class, id);
        assertThat(persistedJson).isEqualTo(draftJson);

        // suppress unused-variable warning on phaseId — it was used for seeding
        assertThat(phaseId).isNotNull();
    }

    // -------------------------------------------------------------------------
    // team rows must be preserved
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PLANNED tournament: resetPlan preserves team rows")
    void resetPlan_planned_preservesTeamRows() {
        UUID id = seedTournament("PLANNED", null);
        seedTeam(id, 1);
        seedTeam(id, 2);

        draftService.resetPlan(id);

        int teamCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM team WHERE tournament_id = ?", Integer.class, id);
        assertThat(teamCount).isEqualTo(2);
    }

    // -------------------------------------------------------------------------
    // Reject: ACTIVE → TournamentResetPlanActiveException
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ACTIVE tournament: resetPlan throws TournamentResetPlanActiveException")
    void resetPlan_active_throwsTypedException() {
        UUID id = seedTournament("ACTIVE", null);

        assertThatThrownBy(() -> draftService.resetPlan(id))
                .isInstanceOf(TournamentResetPlanActiveException.class);
    }

    // -------------------------------------------------------------------------
    // Reject: CANCELLED → TournamentResetPlanCancelledException
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CANCELLED tournament: resetPlan throws TournamentResetPlanCancelledException")
    void resetPlan_cancelled_throwsTypedException() {
        UUID id = seedTournament("CANCELLED", null);

        assertThatThrownBy(() -> draftService.resetPlan(id))
                .isInstanceOf(TournamentResetPlanCancelledException.class);
    }

    // -------------------------------------------------------------------------
    // Reject: COMPLETED → TournamentResetPlanCompletedException
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("COMPLETED tournament: resetPlan throws TournamentResetPlanCompletedException")
    void resetPlan_completed_throwsTypedException() {
        UUID id = seedTournament("COMPLETED", null);

        assertThatThrownBy(() -> draftService.resetPlan(id))
                .isInstanceOf(TournamentResetPlanCompletedException.class);
    }

    // -------------------------------------------------------------------------
    // Reject: DRAFT idempotent → TournamentResetPlanDraftIdempotentException
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "DRAFT tournament: resetPlan throws TournamentResetPlanDraftIdempotentException"
                    + " (idempotent 409, messageKey: error.tournament.resetPlan.draftIdempotent)")
    void resetPlan_draft_throwsIdempotentException() {
        UUID id = seedTournament("DRAFT", null);

        assertThatThrownBy(() -> draftService.resetPlan(id))
                .isInstanceOf(TournamentResetPlanDraftIdempotentException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID seedTournament(String status, String draftJson) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, draft_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                locationId,
                "ResetPlan IT Tournament (" + status + ")",
                "BEST_OF_3",
                "defaultScoringRule",
                "defaultSetValidationRule",
                "roundRobinMatchGenerator",
                status,
                LocalDateTime.now(),
                4,
                8,
                draftJson);
        return id;
    }

    private UUID seedPhase(UUID tournamentId) {
        UUID phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description,"
                        + " status, current_lap_number, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "PENDING",
                0,
                LocalDateTime.now());
        return phaseId;
    }

    private UUID seedTeam(UUID tournamentId, int teamNumber) {
        UUID teamId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO team (id, tournament_id, team_number, description,"
                        + " participate, referee_assignment, without_assessment)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                teamId,
                tournamentId,
                teamNumber,
                "Team " + teamNumber,
                true,
                false,
                false);
        return teamId;
    }
}
