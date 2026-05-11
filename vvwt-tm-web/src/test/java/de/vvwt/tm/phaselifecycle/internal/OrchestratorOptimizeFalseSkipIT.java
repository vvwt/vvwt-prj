package de.vvwt.tm.phaselifecycle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.phaselifecycle.JobDrainService;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJob;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.UUID;
import javax.sql.DataSource;
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
 * RED-first IT for optimize=false L3-skip — AC-TEST-OPTIMIZE-FALSE-SKIP-SLOTOPT-RED.
 *
 * <p>DEC-22 Iron Law: RED before GREEN. At RED time, {@code drainNext()} throws {@code
 * UnsupportedOperationException}. GREEN state: roundRobin phase with optimize=FALSE has matches
 * with non-null lap+field (L1+L2 ran), phase.optimized=FALSE, SlotOpt NOT invoked.
 *
 * <p>Per DEC-56 D-1 amendment: L1+L2 always run regardless of {@code tournament.optimize}. Only L3
 * is skipped when {@code optimize=false}. Matches therefore have {@code lapNumber} and {@code
 * fieldNumber} set from L2's deterministic baseline assignment.
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-44, DEC-55 D-5 (amended by DEC-56), DEC-56 D-1,
 * DEC-64 D-12.
 *
 * @since E55S04
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:orchestratoroptfalseit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, OrchestratorOptimizeFalseSkipIT.SlotOptConfig.class})
@DisplayName("OrchestratorOptimizeFalseSkipIT — AC-TEST-OPTIMIZE-FALSE-SKIP-SLOTOPT-RED (E55S04)")
class OrchestratorOptimizeFalseSkipIT {

    /**
     * Spy that throws AssertionError if called — SlotOpt must NOT be invoked when optimize=false.
     */
    @TestConfiguration
    static class SlotOptConfig {
        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {
                throw new AssertionError(
                        "SlotOptimizationClient.optimize() must NOT be called when"
                                + " tournament.optimize=FALSE (DEC-56 D-1 / DEC-55 D-5)");
            };
        }
    }

    @Autowired private JobDrainService jobDrainService;
    @Autowired private PhaseLifecycleJobRepository jobRepository;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "OptFalse IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "OptFalse IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                4,
                false); // optimize=FALSE → skip L3

        phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "PENDING",
                0,
                false);

        for (int i = 1; i <= 4; i++) {
            UUID teamId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    teamId,
                    tournamentId,
                    i,
                    "Team " + i,
                    true,
                    LocalDateTime.now());
            UUID avatarId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                            + " group_position, team_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    avatarId,
                    tournamentId,
                    phaseId,
                    1,
                    i,
                    teamId);
        }

        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId, "roundRobin", 1));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.update(
                    "DELETE FROM match WHERE phase_id IN"
                            + " (SELECT id FROM phase WHERE tournament_id = ?)",
                    tournamentId);
            jdbcTemplate.update(
                    "DELETE FROM phase_lifecycle_job WHERE tournament_id = ?", tournamentId);
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

    /**
     * AC-TEST-OPTIMIZE-FALSE-SKIP-SLOTOPT-RED: optimize=FALSE tournament gets L1+L2 but NOT L3.
     * Matches present, lap+field from L2, optimized=FALSE.
     */
    @Test
    @DisplayName("optimize=false: L1+L2 run (matches have lap+field), L3 skipped, optimized=FALSE")
    void optimizeFalseRunsL1L2SkipsL3() {
        // When
        jobDrainService.drainNext(tournamentId);

        // Then: job COMPLETED
        String jobStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE tournament_id = ?",
                        String.class,
                        tournamentId);
        assertThat(jobStatus).as("job must be COMPLETED").isEqualTo("COMPLETED");

        // Then: phase is PREPARED, optimized=FALSE (L3 was skipped)
        String phaseStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase WHERE id = ?", String.class, phaseId);
        assertThat(phaseStatus).as("phase must be PREPARED").isEqualTo("PREPARED");

        Boolean optimized =
                jdbcTemplate.queryForObject(
                        "SELECT optimized FROM phase WHERE id = ?", Boolean.class, phaseId);
        assertThat(optimized).as("phase optimized must be false (L3 skipped)").isFalse();

        // Then: matches PRESENT with non-null lap+field (L1+L2 always run per DEC-56 D-1)
        Integer matchCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ?", Integer.class, phaseId);
        assertThat(matchCount)
                .as("L1+L2 must run even when optimize=false (DEC-56 D-1)")
                .isGreaterThan(0);

        Integer nullLapCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ? AND lap_number IS NULL",
                        Integer.class,
                        phaseId);
        assertThat(nullLapCount)
                .as("L2 must assign lap_number even when optimize=false (DEC-56 D-1 amendment)")
                .isEqualTo(0);

        Integer nullFieldCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ? AND field_number IS NULL",
                        Integer.class,
                        phaseId);
        assertThat(nullFieldCount)
                .as("L2 must assign field_number even when optimize=false (DEC-56 D-1 amendment)")
                .isEqualTo(0);
    }
}
