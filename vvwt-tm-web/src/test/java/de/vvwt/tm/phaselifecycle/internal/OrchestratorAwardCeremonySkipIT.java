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
 * RED-first IT for award-ceremony-phase L3-skip — AC-TEST-AWARD-CEREMONY-SKIP-SLOTOPT-RED.
 *
 * <p>Renamed from {@code OrchestratorSiegerehrungSkipIT} by E58S04 (DEC-73 D-7).
 *
 * <p>DEC-22 Iron Law: RED before GREEN. At RED time, {@code drainNext()} throws {@code
 * UnsupportedOperationException}. GREEN state: awardCeremony phase is PREPARED, optimized=FALSE, no
 * matches, and {@code SlotOptimizationClient.optimize()} is NOT invoked.
 *
 * <p>The tournament's {@code draftJson} contains one section with {@code gameMode="awardCeremony"}.
 * Per DEC-59 Clause F (literal updated by E58S04 per DEC-73 D-7), the orchestrator skips L3 for
 * awardCeremony phases.
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-44, DEC-59 Clause F, DEC-55 D-6, DEC-64 D-12,
 * DEC-73 D-7.
 *
 * @since E55S04
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:orchestratorawardit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, OrchestratorAwardCeremonySkipIT.SlotOptConfig.class})
@DisplayName("OrchestratorAwardCeremonySkipIT — AC-TEST-AWARD-CEREMONY-SKIP-SLOTOPT-RED (E55S04)")
class OrchestratorAwardCeremonySkipIT {

    /**
     * SlotOptimizationClient that throws AssertionError if called — SlotOpt must NOT be invoked for
     * awardCeremony phases (DEC-59 Clause F). If the test reaches its assertions without this bean
     * throwing, the contract is satisfied.
     */
    @TestConfiguration
    static class SlotOptConfig {

        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {
                throw new AssertionError(
                        "SlotOptimizationClient.optimize() must NOT be called"
                                + " for awardCeremony phase (DEC-59 Clause F)");
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
    void setUp() throws Exception {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "Award IT Location");

        // Build draftJson with one awardCeremony section (renamed from siegerehrung by E58S04)
        String draftJson =
                "{\"sections\":[{\"name\":\"Award Ceremony\",\"gameMode\":\"awardCeremony\","
                        + "\"groupCount\":1,\"teamCount\":4,\"distributionMode\":\"MANUAL\"}]}";

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize, draft_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "Award Ceremony IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                4,
                true, // optimize=true, but awardCeremony bypasses L3
                draftJson);

        phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1, // sequenceNumber=1 → index 0 in draftJson.sections → "awardCeremony"
                "Award Ceremony Phase",
                "PENDING",
                0,
                false);

        // Insert team_avatars for awardCeremony phase (required even if no matches generated)
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

        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId, "awardCeremony", 1));
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
     * AC-TEST-AWARD-CEREMONY-SKIP-SLOTOPT-RED: awardCeremony phase completes with no matches,
     * phase.optimized=FALSE, phase.status=PREPARED. SlotOptimizationClient is NOT invoked.
     */
    @Test
    @DisplayName(
            "awardCeremony phase: PREPARED, optimized=FALSE, no matches, SlotOpt not called"
                    + " (AC4, E58S04)")
    void awardCeremonyPhaseSkipsSlotOpt() {
        // When
        jobDrainService.drainNext(tournamentId);

        // Then: job is COMPLETED
        String jobStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase_lifecycle_job WHERE tournament_id = ?",
                        String.class,
                        tournamentId);
        assertThat(jobStatus).as("job must be COMPLETED").isEqualTo("COMPLETED");

        // Then: phase is PREPARED, optimized=FALSE (DEC-59 Clause F + DEC-55 D-6)
        String phaseStatus =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase WHERE id = ?", String.class, phaseId);
        assertThat(phaseStatus).as("phase must be PREPARED").isEqualTo("PREPARED");

        Boolean optimized =
                jdbcTemplate.queryForObject(
                        "SELECT optimized FROM phase WHERE id = ?", Boolean.class, phaseId);
        assertThat(optimized).as("phase optimized must be false for awardCeremony").isFalse();

        // Then: no matches (awardCeremony L1 returns empty list, L2 is vacuous)
        Integer matchCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ?", Integer.class, phaseId);
        assertThat(matchCount)
                .as("awardCeremony phase must have 0 matches (DEC-59 Clause E)")
                .isEqualTo(0);

        // Then: SlotOptimizationClient.optimize() was NOT invoked (verify via AssertionError spy)
        // The spy throws AssertionError on any call — if we reach here, it was not called.
    }
}
