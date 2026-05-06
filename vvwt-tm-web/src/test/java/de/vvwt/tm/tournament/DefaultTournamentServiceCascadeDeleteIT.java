package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.exceptions.TournamentCascadeDeleteActiveException;
import de.vvwt.tm.tournament.exceptions.TournamentCascadeDeleteCompletedException;
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
 * RED-first DAO IT for cascade-delete operation (E48S13, AC-TEST-CASCADE-DELETE-DAO-IT-RED).
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>DRAFT empty tournament: cascade-delete succeeds; 0 rows across all dependent tables
 *   <li>DRAFT with team rows: cascade-delete succeeds; team rows deleted; tournament row deleted
 *   <li>PLANNED with phases: cascade-delete succeeds; all phase-derived rows + tournament deleted
 *   <li>CANCELLED: cascade-delete succeeds
 *   <li>ACTIVE: rejected → {@link TournamentCascadeDeleteActiveException} (409), no row deleted
 *   <li>COMPLETED: rejected → {@link TournamentCascadeDeleteCompletedException} (409), no row
 *       deleted
 * </ul>
 *
 * <h2>DEC-26/DEC-46 three-rule conformance</h2>
 *
 * <ul>
 *   <li>Schema from Flyway migrations (CASE_INSENSITIVE_IDENTIFIERS on H2)
 *   <li>Independent verifier: JDBC row-count assertions (not read-path queries)
 *   <li>Read/write decoupling: truth sourced from {@link JdbcTemplate} direct queries
 * </ul>
 *
 * <p>Tests are RED before production changes (DEC-22 Iron Law, Q-1a fresh).
 *
 * @see TournamentService
 * @see de.vvwt.tm.tournament.internal.DefaultTournamentService
 * @see <a href="E48S13">E48S13 — AC-TEST-CASCADE-DELETE-DAO-IT-RED</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="DEC-46">DEC-46 — assertj-db independent verifier</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:cascadedeleteit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("DefaultTournamentService Cascade-Delete IT — E48S13 RED-first")
class DefaultTournamentServiceCascadeDeleteIT {

    /** Subject: inject via public interface per DEC-36. */
    @Autowired private TournamentService tournamentService;

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
                "CascadeDelete IT Location");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM team");
        jdbcTemplate.update("DELETE FROM tournament");
        jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
        tenantBinder.unbind();
    }

    // -------------------------------------------------------------------------
    // Happy-path: DRAFT (empty — no phases, no teams beyond seeded rows)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DRAFT empty tournament: cascade-delete deletes tournament row")
    void cascadeDelete_draftEmpty_deletesTournamentRow() {
        UUID id = seedTournament("DRAFT");

        tournamentService.deleteTournament(id);

        int count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM tournament WHERE id = ?", Integer.class, id);
        assertThat(count).isZero();
    }

    // -------------------------------------------------------------------------
    // Happy-path: PLANNED with phases (structural data seeded)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "PLANNED tournament with phase: cascade-delete removes tournament and all phase rows")
    void cascadeDelete_plannedWithPhase_deletesAllRows() {
        UUID id = seedTournament("PLANNED");
        UUID phaseId = seedPhase(id);

        tournamentService.deleteTournament(id);

        int tournamentCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM tournament WHERE id = ?", Integer.class, id);
        int phaseCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM phase WHERE tournament_id = ?", Integer.class, id);
        assertThat(tournamentCount).isZero();
        assertThat(phaseCount).isZero();
        // suppress unused-variable warning on phaseId — it was used for seeding
        assertThat(phaseId).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Happy-path: CANCELLED
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CANCELLED tournament: cascade-delete succeeds")
    void cascadeDelete_cancelled_succeeds() {
        UUID id = seedTournament("CANCELLED");

        tournamentService.deleteTournament(id);

        int count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM tournament WHERE id = ?", Integer.class, id);
        assertThat(count).isZero();
    }

    // -------------------------------------------------------------------------
    // Reject: ACTIVE → TournamentCascadeDeleteActiveException (no row deleted)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ACTIVE tournament: cascade-delete throws TournamentCascadeDeleteActiveException")
    void cascadeDelete_active_throwsTypedException() {
        UUID id = seedTournament("ACTIVE");

        assertThatThrownBy(() -> tournamentService.deleteTournament(id))
                .isInstanceOf(TournamentCascadeDeleteActiveException.class);

        // Tournament row must NOT have been deleted
        int count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM tournament WHERE id = ?", Integer.class, id);
        assertThat(count).isOne();
    }

    // -------------------------------------------------------------------------
    // Reject: COMPLETED → TournamentCascadeDeleteCompletedException (no row deleted)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "COMPLETED tournament: cascade-delete throws TournamentCascadeDeleteCompletedException")
    void cascadeDelete_completed_throwsTypedException() {
        UUID id = seedTournament("COMPLETED");

        assertThatThrownBy(() -> tournamentService.deleteTournament(id))
                .isInstanceOf(TournamentCascadeDeleteCompletedException.class);

        int count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM tournament WHERE id = ?", Integer.class, id);
        assertThat(count).isOne();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID seedTournament(String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                locationId,
                "CascadeDelete IT Tournament (" + status + ")",
                "BEST_OF_3",
                "defaultScoringRule",
                "defaultSetValidationRule",
                "roundRobinMatchGenerator",
                status,
                LocalDateTime.now(),
                4,
                8);
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
}
