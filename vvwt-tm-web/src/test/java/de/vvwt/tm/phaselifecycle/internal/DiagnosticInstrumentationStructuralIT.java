package de.vvwt.tm.phaselifecycle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.phaselifecycle.JobDrainService;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJob;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Structural-simulation IT for E55S10 diagnostic instrumentation —
 * AC-DIAG-VERIFY-INSTRUMENTATION-VIA-STRUCTURAL-IT.
 *
 * <h2>Scope</h2>
 *
 * <p>Verifies the Spring TX-boundary instrumentation ({@code tm.diagnostics.spring-tx-trace=true})
 * is active and produces parseable log output for every {@code REQUIRES_NEW} TX in a job-drain run
 * (step-A + step-B). This satisfies the headless-deliverable scope of
 * AC-DIAG-VERIFY-INSTRUMENTATION-VIA-STRUCTURAL-IT.
 *
 * <p>The HikariCP connection-lifecycle instrumentation ({@code tm.diagnostics.hikari-trace=true})
 * is exercised separately in {@code DiagnosticDataSourceWrapperTest} (unit test) because {@code
 * TenantContextTestSupport} substitutes an in-memory {@code TenantDataSourceResolver} (bypassing
 * the production {@code TenantFileRegistryDataSourceResolver} wrapping logic). This is a necessary
 * artefact of the Spring Boot test isolation design (DEC-21).
 *
 * <h2>AC-DIAG-IDENTIFY-PRODUCER-FROM-STRUCTURAL-IT findings</h2>
 *
 * <p>The structural IT does NOT reproduce any of the anomalous patterns (ROLLED_BACK, UNKNOWN,
 * autoCommit=false, pool-aliasing) in the happy-path single-tenant scenario. The producer is
 * operator-workflow-specific (deferred to E55S11 per
 * AC-DIAG-OPERATOR-INSTRUMENTATION-INSTRUCTIONS).
 *
 * <h2>AC-DIAG-RULE-IN-OUT-HYPOTHESES-VIA-STRUCTURAL-IT</h2>
 *
 * <ul>
 *   <li>H-2 (Spring TX boundary mismatch): RULED OUT — all REQUIRES_NEW TXes commit with COMMITTED
 *       status in the happy-path; no ROLLED_BACK or UNKNOWN observed.
 *   <li>H-3 (DataSource cache stale): RULED OUT — static analysis; ConcurrentHashMap monotonic.
 *   <li>H-5 (EventPublicationRegistry replay): RULED OUT — no handler registered post-DEC-64.
 *   <li>H-6 (H2 multi-DataSource race): RULED OUT — single DataSource per tenant (static analysis).
 *   <li>H-1b (raw H2 concurrent connection race): INCONCLUSIVE-FROM-STRUCTURAL-IT — single-threaded
 *       happy-path does not exercise concurrent H2 access.
 *   <li>H-4 (HikariCP autoCommit reset): INCONCLUSIVE-FROM-STRUCTURAL-IT — raw JdbcDataSource per
 *       tenant (not HikariCP-pooled); autoCommit is H2 default=true per-connection, so pool-reset
 *       anomaly is not observable in this path.
 * </ul>
 *
 * <p>Authorizing decisions: DEC-22 (RED-first), DEC-44 (NONE web environment), DEC-64 D-12, E55S10
 * AC-DIAG-VERIFY-INSTRUMENTATION-VIA-STRUCTURAL-IT.
 *
 * @since E55S10
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:diagit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "tm.diagnostics.spring-tx-trace=true"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, DiagnosticInstrumentationStructuralIT.SlotOptConfig.class})
@DisplayName(
        "DiagnosticInstrumentationStructuralIT — AC-DIAG-VERIFY-INSTRUMENTATION-VIA-STRUCTURAL-IT"
                + " (E55S10)")
class DiagnosticInstrumentationStructuralIT {

    @TestConfiguration
    static class SlotOptConfig {
        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {};
        }
    }

    @Autowired private JobDrainService jobDrainService;
    @Autowired private PhaseLifecycleJobRepository jobRepository;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;

    /**
     * Logback ListAppender capturing lines from DiagnosticTransactionSupport.
     * DiagnosticTransactionSupport is in the same package (phaselifecycle.internal) — directly
     * accessible.
     */
    private ListAppender<ILoggingEvent> txAppender;

    private Logger txLogger;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        // Attach in-memory appender to the Spring TX-boundary diagnostic logger
        txLogger = (Logger) LoggerFactory.getLogger(DiagnosticTransactionSupport.class);
        txAppender = new ListAppender<>();
        txAppender.start();
        txLogger.addAppender(txAppender);

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "DiagIT Location");

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "DiagIT Tournament",
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

        // Insert 4 participating teams
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
        if (txLogger != null && txAppender != null) {
            txLogger.detachAppender(txAppender);
        }
    }

    /**
     * Verifies that a full job drain (step-A + step-B) produces at least two {@code [diag-tx]
     * REGISTER} + two {@code [diag-tx] COMPLETE} log lines with COMMITTED status.
     *
     * <p>Each REQUIRES_NEW TX (step-A in {@link OrchestratorStepAExecutor}, step-B in {@link
     * OrchestratorStepBExecutor}) must register and complete with COMMITTED status in the
     * happy-path, validating that the Spring TX-boundary instrumentation is active and parseable.
     */
    @Test
    @DisplayName("happy-path drain emits [diag-tx] REGISTER + COMPLETE(COMMITTED) for each TX")
    void happyPathDrainEmitsSpringTxBoundaryLogs() {
        jobDrainService.drainNext(tournamentId);

        List<ILoggingEvent> txEvents = txAppender.list;
        List<String> txMessages =
                txEvents.stream().map(ILoggingEvent::getFormattedMessage).toList();

        // At least 2 REGISTER events (one per REQUIRES_NEW TX: step-A + step-B)
        long registerCount =
                txMessages.stream().filter(msg -> msg.contains("[diag-tx] REGISTER")).count();
        assertThat(registerCount)
                .as(
                        "[diag-tx] REGISTER must be emitted for each REQUIRES_NEW TX"
                                + " (min 2: step-A + step-B)")
                .isGreaterThanOrEqualTo(2);

        // At least 2 COMPLETE events (one per REQUIRES_NEW TX: step-A + step-B)
        long completeCount =
                txMessages.stream().filter(msg -> msg.contains("[diag-tx] COMPLETE")).count();
        assertThat(completeCount)
                .as(
                        "[diag-tx] COMPLETE must be emitted for each REQUIRES_NEW TX"
                                + " (min 2: step-A + step-B)")
                .isGreaterThanOrEqualTo(2);

        // All COMPLETE events must carry COMMITTED status in the happy-path (H-2 RULED OUT)
        List<String> completeMessages =
                txMessages.stream().filter(msg -> msg.contains("[diag-tx] COMPLETE")).toList();
        assertThat(completeMessages)
                .as(
                        "all [diag-tx] COMPLETE events must have COMMITTED status in happy-path"
                                + " — H-2 (Spring TX boundary mismatch) RULED OUT for this path")
                .allMatch(msg -> msg.contains("COMMITTED"));

        // All events must be at INFO level (AC-SEC-NO-DEBUG-LEAK-IN-PROD: no DEBUG/TRACE)
        assertThat(txEvents)
                .as("all [diag-tx] events must be logged at INFO level")
                .allMatch(e -> e.getLevel() == Level.INFO);
    }
}
