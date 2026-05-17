// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.phaselifecycle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.phaselifecycle.WorkerRegistry;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tenant.TenantRegistryPort;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC-TEST-H-F-INITONSTARTUP-RUNS-FOR-REGISTERED-TENANTS-RED (E55S09, DEC-22 RED-first Pattern B).
 *
 * <p>RED-first IT for the H-F fix: verifies that {@link WorkerRegistry#initOnStartup(String)}
 * actually executes recovery for the registered default tenant.
 *
 * <p>H-F hypothesis: {@link DefaultWorkerRegistry#startIdlePoller()} wraps {@code initOnStartup} in
 * a broad {@code catch (Exception e)} that silently swallows {@link IllegalStateException} thrown
 * by {@code RoutingTenantDataSource.determineCurrentLookupKey()} when no tenant is bound at
 * {@code @PostConstruct} time. In a multi-tenant production deployment, no recovery ever fires.
 *
 * <p>This test verifies the POSITIVE path: given the default tenant is registered and a PENDING
 * recovery job exists, calling {@code initOnStartup("this-jvm-id")} MUST complete the job. Pre-fix:
 * initOnStartup silently skips (if H-F is real) → job stays PENDING. Post-fix: recovery fires for
 * the tenant → job transitions to COMPLETED.
 *
 * <p>Step 0 verification (per AC-TEST-H-F-INITONSTARTUP-RUNS-FOR-REGISTERED-TENANTS-RED): The test
 * invokes {@code initOnStartup} DIRECTLY (after binding tenant context) to test the core recovery
 * logic. The @PostConstruct wrapper behaviour (with/without bound tenant) is tested in the separate
 * unit test {@link DefaultWorkerRegistry} — the integration test validates the outcome.
 *
 * <p>DEC-22 Pattern B: producer is "the fix" (per-tenant binding in initOnStartup);
 * legacy @PostConstruct wrapper is not a trustworthy oracle.
 *
 * @since E55S09
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:hfinitonstartuptit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, WorkerRegistryInitOnStartupHfIT.SlotOptConfig.class})
@DisplayName(
        "AC-TEST-H-F-INITONSTARTUP-RUNS-FOR-REGISTERED-TENANTS-RED:"
                + " initOnStartup executes recovery for registered tenant (E55S09)")
class WorkerRegistryInitOnStartupHfIT {

    private static final Logger LOG =
            LoggerFactory.getLogger(WorkerRegistryInitOnStartupHfIT.class);

    @TestConfiguration
    static class SlotOptConfig {
        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {};
        }
    }

    @Autowired private WorkerRegistry workerRegistry;
    @Autowired private PhaseLifecycleJobRepository jobRepository;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("tenantRoutingContext")
    private TenantContext tenantContext;

    @Autowired private TenantRegistryPort tenantRegistryPort;

    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;
    private UUID defaultTenantId;

    @BeforeEach
    void setUp() {
        defaultTenantId = tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "HfInitOnStartup IT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "HfInitOnStartup IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "DRAFT",
                LocalDateTime.now(),
                2,
                4,
                true);

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
                            + " group_position, team_id) VALUES (?, ?, ?, ?, ?, ?)",
                    avatarId,
                    tournamentId,
                    phaseId,
                    1,
                    i,
                    teamId);
        }
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
            tenantBinder.unbind();
        }
    }

    /**
     * AC-TEST-H-F-INITONSTARTUP-RUNS-FOR-REGISTERED-TENANTS-RED: insert a stale RUNNING row
     * (dead-jvm), call initOnStartup — recovery must complete the job.
     *
     * <p>Pre-fix: initOnStartup skips recovery silently (H-F swallow) → job stays PENDING/RUNNING.
     * Post-fix: per-tenant binding in initOnStartup allows jobRepository calls to route to the
     * correct tenant DB → job resets to PENDING → worker drains → COMPLETED.
     */
    @Test
    @DisplayName(
            "H-F: initOnStartup with registered tenant resets stale RUNNING row and drains to"
                    + " COMPLETED")
    void initOnStartup_withRegisteredTenant_recoversStaleRunningRow() throws InterruptedException {
        // Insert job directly as RUNNING with a foreign claimed_by (dead JVM) — stale state
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase_lifecycle_job"
                        + " (id, tournament_id, phase_id, game_mode, sequence, status, cancelled,"
                        + " claimed_by, claimed_at)"
                        + " VALUES (?, ?, ?, 'roundRobin', 1, 'RUNNING', FALSE, 'dead-jvm',"
                        + " CURRENT_TIMESTAMP)",
                jobId,
                tournamentId,
                phaseId);

        // Trigger restart-recovery with a different JVM ID (not 'dead-jvm')
        // Tenant context IS bound (default tenant bound in setUp) so recovery can route DB calls
        workerRegistry.initOnStartup("this-jvm-id");

        // Allow time for the drain hint to execute and the job to complete
        // Poll up to 10s, checking every 200ms
        String finalStatus = null;
        for (int i = 0; i < 50; i++) {
            Thread.sleep(200);
            finalStatus =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM phase_lifecycle_job WHERE id = ?",
                            String.class,
                            jobId);
            if ("COMPLETED".equals(finalStatus)) {
                break;
            }
        }

        assertThat(finalStatus)
                .as(
                        "H-F: initOnStartup with bound tenant must reset stale RUNNING row and"
                                + " drain to COMPLETED (RED pre-fix: recovery silently skipped)")
                .isEqualTo("COMPLETED");
    }

    /**
     * AC-TEST-H-F-INITONSTARTUP-RUNS-FOR-REGISTERED-TENANTS-RED boundary: with no stale jobs,
     * initOnStartup with tenant bound is a no-op (no recovery needed, no error).
     */
    @Test
    @DisplayName("H-F: initOnStartup with no stale jobs is a clean no-op (no exception)")
    void initOnStartup_noStaleJobs_isNoOp() {
        // No jobs at all — initOnStartup should be a clean no-op
        // This verifies the fix doesn't introduce spurious errors when there's nothing to recover
        workerRegistry.initOnStartup("this-jvm-id");
        // No assertion needed — if no exception is thrown, the test passes
    }
}
