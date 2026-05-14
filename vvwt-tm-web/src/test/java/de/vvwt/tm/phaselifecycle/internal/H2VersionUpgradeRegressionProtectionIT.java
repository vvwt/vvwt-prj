package de.vvwt.tm.phaselifecycle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.phaselifecycle.JobDrainService;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJob;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tenant.TenantDataSourceResolver;
import de.vvwt.tm.tournament.DeviceService;
import de.vvwt.tm.tournament.Phase.PhaseStatus;
import de.vvwt.tm.tournament.PhaseLifecycleService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * DEC-22 RED-first recurrence-protection IT for H2 version upgrade 2.3.232 → 2.4.240 —
 * AC-TEST-AUTOMATED-RECURRENCE-PROTECTION-IT (E55S12).
 *
 * <h2>Purpose</h2>
 *
 * <p>This IT is the DEC-22 RED-first artefact for the H2 version-bump in {@code pom.xml:70}. It
 * verifies that the M-2/M-3/M-5/Display-token-loss bug class (empirically eliminated under H2
 * 2.4.240 per in-session Stair-0b + Stair-0b-min operator verification on 2026-05-14) is
 * regression-protected by an automated harness.
 *
 * <h2>Bug class under H2 2.3.232</h2>
 *
 * <ul>
 *   <li><b>M-2</b>: phase.status regression ACTIVE→ASSIGNED after Device.configure()
 *   <li><b>M-3</b>: tournament.status regression ACTIVE→PLANNED after Phase 1 start
 *   <li><b>M-5</b>: devices row disappearance post-configure
 *   <li><b>Display-token-loss</b>: device_token invalidated after phase activation
 * </ul>
 *
 * <h2>ROOT CAUSE per E55S11 audit-report + H2 2.4.240 changelog</h2>
 *
 * <ul>
 *   <li>H2 Issue #4247 "Compaction causes missing chunks and data loss" (H-B'(b2))
 *   <li>H2 Issue #4208 "Lost update with SELECT FOR UPDATE" (H-Z hypothesis)
 * </ul>
 *
 * <h2>DEC-22 RED-first verification protocol</h2>
 *
 * <ol>
 *   <li>Commit this IT class WITHOUT the {@code pom.xml} h2.version bump → run N cycles under H2
 *       2.3.232 → expect RED (at least 1 cycle fails assertions).
 *   <li>Commit the {@code pom.xml} bump (2.3.232 → 2.4.240) → run N cycles under H2 2.4.240 →
 *       expect GREEN (all cycles pass).
 * </ol>
 *
 * <h2>File-based H2 configuration</h2>
 *
 * <p>This IT uses file-based H2 (not the in-memory H2 provided by {@link TenantContextTestSupport})
 * because the MVStore compaction bug (H-B'(b2) / H2 Issue #4247) only manifests in file mode. Each
 * test cycle uses fresh file-based H2 databases created in a shared class-level temp directory,
 * ensuring isolation between cycles.
 *
 * <h2>Logging configuration (v4-class minimal — preserves Heisenbug race window)</h2>
 *
 * <p>Per E55S11 empirical evidence: logging at {@code org.h2=DEBUG} or {@code
 * StatementCreatorUtils=TRACE} level suppresses the bug (forces H2 flush paths that avoid the
 * compaction race). This IT uses v4-class minimal logging — only {@code
 * logging.level.de.vvwt.tm=DEBUG} — to preserve the race window that allowed the bug to manifest in
 * v4/v5/v6 operator reproductions.
 *
 * <h2>Recurrence protection cycles</h2>
 *
 * <p>Default N=10 cycles. Configurable via {@code tm.test.recurrence-protection-cycles} property
 * (range 1–30). If RED cannot be achieved in N=30 under H2 2.3.232, escalate per
 * AC-ERROR-HANDLING-RED-FIRST-IT-CANNOT-BE-MADE-DETERMINISTIC.
 *
 * <h2>Per-cycle assertions</h2>
 *
 * <p>After the full operator workflow (create tournament → apply draft → run orchestrator →
 * activate phase 1 → register Display device → configure display), each cycle asserts:
 *
 * <ul>
 *   <li>{@code phase.status == 'ACTIVE'}
 *   <li>{@code tournament.status == 'ACTIVE'}
 *   <li>{@code tournament.active_sentinel IS NOT NULL}
 *   <li>{@code COUNT(devices) >= 1}
 * </ul>
 *
 * <p>Authorizing decisions: DEC-22 (RED-first harness), DEC-44 (SpringBootTest NONE), DEC-64
 * (Saga-Orchestrator), DEC-14 (H2 embedded), DEC-20 (DB-per-Tenant).
 *
 * @since E55S12
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:h2upgraderpit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            // v4-class minimal logging — preserves the Heisenbug race window per E55S11 evidence.
            // DO NOT add org.h2=DEBUG, jdbc.core.JdbcTemplate=DEBUG, transaction=DEBUG, or
            // org.springframework.transaction.interceptor=TRACE — these suppress the bug.
            "logging.level.de.vvwt.tm=DEBUG",
            "logging.level.org.springframework=WARN",
            "logging.level.org.h2=WARN"
        })
@Import({
    TenantContextTestSupport.class,
    H2VersionUpgradeRegressionProtectionIT.FileTenantDsConfig.class,
    H2VersionUpgradeRegressionProtectionIT.SlotOptConfig.class
})
@DisplayName(
        "H2VersionUpgradeRegressionProtectionIT — AC-TEST-AUTOMATED-RECURRENCE-PROTECTION-IT"
                + " (E55S12)")
class H2VersionUpgradeRegressionProtectionIT {

    // -------------------------------------------------------------------------
    // Temp directory for file-based H2 tenant databases
    // -------------------------------------------------------------------------

    /**
     * Shared class-level temp directory for file-based H2 databases. Created once per test class.
     */
    private static Path CLASS_TEMP_DIR;

    @BeforeAll
    static void createTempDir() throws IOException {
        CLASS_TEMP_DIR = Files.createTempDirectory("h2upgrade-regressionit-");
    }

    @AfterAll
    static void deleteTempDir() throws IOException {
        if (CLASS_TEMP_DIR != null && Files.exists(CLASS_TEMP_DIR)) {
            try (Stream<Path> walk = Files.walk(CLASS_TEMP_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    // -------------------------------------------------------------------------
    // @TestConfiguration: file-based H2 TenantDataSourceResolver
    // -------------------------------------------------------------------------

    /**
     * Overrides the in-memory TenantDataSourceResolver from {@link TenantContextTestSupport} with a
     * file-based H2 resolver.
     *
     * <p>Each tenant UUID gets its own file-based H2 database under {@link #CLASS_TEMP_DIR}. This
     * uses the same JDBC URL parameters as production ({@link
     * de.vvwt.tm.tenant.internal.TenantFileRegistryDataSourceResolver}) to ensure
     * production-faithful H2 behaviour (MVStore compaction paths, SELECT FOR UPDATE semantics).
     */
    @TestConfiguration
    static class FileTenantDsConfig {

        @Bean
        @Primary
        TenantDataSourceResolver fileBasedTenantDataSourceResolver(
                DataSourceProperties dataSourceProperties) {
            return new FileBasedTenantDataSourceResolver(dataSourceProperties);
        }
    }

    /** File-based H2 {@link TenantDataSourceResolver} for regression-protection testing. */
    static final class FileBasedTenantDataSourceResolver implements TenantDataSourceResolver {

        private final ConcurrentHashMap<UUID, DataSource> cache = new ConcurrentHashMap<>();
        private final DataSourceProperties dataSourceProperties;

        FileBasedTenantDataSourceResolver(DataSourceProperties dataSourceProperties) {
            this.dataSourceProperties = dataSourceProperties;
        }

        @Override
        public DataSource resolve(UUID tenantId) {
            return cache.computeIfAbsent(
                    tenantId,
                    id -> {
                        // Create tenant directory under class-level temp dir
                        Path tenantDir = CLASS_TEMP_DIR.resolve(id.toString());
                        tenantDir.toFile().mkdirs();
                        String dbPath = tenantDir.resolve("db").toAbsolutePath().toString();
                        // Production-faithful JDBC URL (matches
                        // TenantFileRegistryDataSourceResolver:166)
                        String jdbcUrl =
                                "jdbc:h2:file:"
                                        + dbPath
                                        + ";AUTO_SERVER=FALSE"
                                        + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE"
                                        + ";DB_CLOSE_ON_EXIT=FALSE";
                        JdbcDataSource ds = new JdbcDataSource();
                        ds.setURL(jdbcUrl);
                        ds.setUser(dataSourceProperties.determineUsername());
                        ds.setPassword(dataSourceProperties.determinePassword());
                        return ds;
                    });
        }
    }

    // -------------------------------------------------------------------------
    // @TestConfiguration: no-op SlotOptimizationClient
    // -------------------------------------------------------------------------

    /** No-op slot-opt override: returns immediately (optimize=false skips L3 anyway). */
    @TestConfiguration
    static class SlotOptConfig {

        @Bean("routingSlotOptimizationClient")
        @Primary
        SlotOptimizationClient testSlotOptimizationClient() {
            return phaseId -> {
                // No-op: optimize=false in this IT — L3 is never invoked
            };
        }
    }

    // -------------------------------------------------------------------------
    // Injected services
    // -------------------------------------------------------------------------

    @Autowired private JobDrainService jobDrainService;
    @Autowired private PhaseLifecycleJobRepository jobRepository;
    @Autowired private PhaseLifecycleService phaseLifecycleService;
    @Autowired private DeviceService deviceService;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;

    @Autowired
    @Qualifier("routingTenantDataSource")
    private DataSource routingDataSource;

    // -------------------------------------------------------------------------
    // Per-test state
    // -------------------------------------------------------------------------

    /**
     * Number of cycles — configurable via {@code tm.test.recurrence-protection-cycles} (1–30).
     * Default 10.
     */
    @Value("${tm.test.recurrence-protection-cycles:10}")
    private int cyclesToRun;

    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;
    private UUID deviceId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();
    }

    @AfterEach
    void tearDown() {
        tenantBinder.unbind();
    }

    // -------------------------------------------------------------------------
    // Recurrence-protection test
    // -------------------------------------------------------------------------

    /**
     * AC-TEST-AUTOMATED-RECURRENCE-PROTECTION-IT: N cycles of the full operator workflow.
     *
     * <p>For each cycle:
     *
     * <ol>
     *   <li>Set up tournament + phase + teams via SQL (minimal setup matching existing E55 ITs)
     *   <li>Enqueue and drain the orchestrator job (PENDING → PREPARED)
     *   <li>Manually transition PREPARED → ASSIGNED (operator confirmation step)
     *   <li>Start phase: ASSIGNED → ACTIVE (also auto-promotes tournament PLANNED → ACTIVE)
     *   <li>Register DISPLAY device
     *   <li>Configure DISPLAY device (this is the trigger that caused M-2 regression under E1 arch)
     *   <li>Assert: phase.status == ACTIVE, tournament.status == ACTIVE, tournament.active_sentinel
     *       IS NOT NULL, COUNT(devices) >= 1
     * </ol>
     *
     * <p><b>DEC-22 RED-first</b>: Under H2 2.3.232, at least 1 of {N} cycles should expose the
     * MVStore compaction/lost-update bug (H2 Issues #4247 + #4208). Under H2 2.4.240, all cycles
     * pass.
     *
     * <p><b>Heisenbug note</b>: If the bug does not manifest in N=10 cycles under 2.3.232, increase
     * to N=30 via the {@code tm.test.recurrence-protection-cycles} property. If still GREEN at
     * N=30, escalate per AC-ERROR-HANDLING-RED-FIRST-IT-CANNOT-BE-MADE-DETERMINISTIC.
     */
    @Test
    @DisplayName(
            "recurrence-protection: N cycles of full operator workflow — phase+tournament ACTIVE"
                    + " in every cycle (RED under H2 2.3.232, GREEN under H2 2.4.240)")
    void recurrenceProtection_fullWorkflow_nCycles() {
        for (int cycle = 0; cycle < cyclesToRun; cycle++) {
            runOneCycle(cycle);
        }
    }

    // -------------------------------------------------------------------------
    // Per-cycle implementation
    // -------------------------------------------------------------------------

    private void runOneCycle(int cycle) {
        JdbcTemplate jdbc = new JdbcTemplate(routingDataSource);

        // -- Setup: create location, tournament (PLANNED), phase (PENDING), teams + avatars --
        locationId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "H2UpgradeIT-Location-cycle" + cycle);

        tournamentId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "H2UpgradeIT-Tournament-cycle" + cycle,
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "PLANNED", // tournament starts in PLANNED (operator confirmed teams)
                LocalDateTime.now(),
                2, // fieldCount=2
                4, // teamCount=4 → 6 matches, 3 laps
                false); // optimize=false — skip slot-opt (L3), simplifies test

        phaseId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "PENDING", // will be advanced by orchestrator
                0,
                false);

        // Insert 4 teams + team_avatars (required by MatchGenerator L1)
        for (int i = 1; i <= 4; i++) {
            UUID teamId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    teamId,
                    tournamentId,
                    i,
                    "Team " + i,
                    true,
                    LocalDateTime.now());
            UUID avatarId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                            + " group_position, team_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?)",
                    avatarId,
                    tournamentId,
                    phaseId,
                    1, // group 1
                    i, // position i
                    teamId);
        }

        // Enqueue orchestrator job (PENDING → PREPARED via step-A)
        jobRepository.enqueueJob(new PhaseLifecycleJob(tournamentId, phaseId, "roundRobin", 1));

        // Run orchestrator: step-A (match-gen L1 + round-assign L2 + PENDING→PREPARED)
        //                   step-B (optimize=false → skip L3, write idle, job COMPLETED)
        jobDrainService.drainNext(tournamentId);

        // Transition PREPARED → ASSIGNED (operator team-assignment confirmation)
        // Uses PhaseLifecycleService.transition() directly — "assign" verb per DEC-55 D-4
        phaseLifecycleService.transition(phaseId, PhaseStatus.ASSIGNED, "assign");

        // Start phase: ASSIGNED → ACTIVE (auto-promotes tournament PLANNED → ACTIVE per E48S24
        // D-1a)
        // This is the primary trigger for M-3 (tournament regression) in the bug-class.
        phaseLifecycleService.start(phaseId);

        // Register DISPLAY device
        var device = deviceService.register("DISPLAY");
        deviceId = device.getId();

        // Configure DISPLAY device — primary trigger for M-2 (phase regression) in bug-class
        deviceService.configure(deviceId, "Display-" + cycle, null);

        // -- Per-cycle assertions --

        String phaseStatus =
                jdbc.queryForObject("SELECT status FROM phase WHERE id = ?", String.class, phaseId);
        assertThat(phaseStatus)
                .as(
                        "cycle %d: M-2 regression check — phase.status must be ACTIVE after"
                                + " start() + configure(). Under H2 2.3.232 MVStore bug, this"
                                + " reverted to ASSIGNED. Fixed by H2 2.4.240 (Issue #4247 +"
                                + " #4208).",
                        cycle)
                .isEqualTo("ACTIVE");

        String tournamentStatus =
                jdbc.queryForObject(
                        "SELECT status FROM tournament WHERE id = ?", String.class, tournamentId);
        assertThat(tournamentStatus)
                .as(
                        "cycle %d: M-3 regression check — tournament.status must be ACTIVE"
                                + " after Phase 1 start(). Under H2 2.3.232 MVStore bug, this"
                                + " reverted to PLANNED.",
                        cycle)
                .isEqualTo("ACTIVE");

        // active_sentinel is a GENERATED ALWAYS AS column: UUID (= location_id) when ACTIVE, NULL
        // otherwise. We check via a COUNT to avoid H2 UUID ↔ Java type mapping issues.
        Integer activeSentinelCount =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM tournament WHERE id = ? AND active_sentinel IS NOT"
                                + " NULL",
                        Integer.class,
                        tournamentId);
        assertThat(activeSentinelCount)
                .as(
                        "cycle %d: tournament.active_sentinel must be non-null after Phase 1"
                                + " start() (E48S24 D-1a: active_sentinel = location_id for"
                                + " ACTIVE tournaments, NULL otherwise per V1 schema generated"
                                + " column).",
                        cycle)
                .isEqualTo(1);

        int deviceCount =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM devices WHERE id = ?", Integer.class, deviceId);
        assertThat(deviceCount)
                .as(
                        "cycle %d: M-5 regression check — devices row must exist after"
                                + " register() + configure(). Under H2 2.3.232 MVStore bug,"
                                + " device rows could disappear due to compaction data loss.",
                        cycle)
                .isGreaterThanOrEqualTo(1);

        // -- Teardown per cycle (clean up for next cycle) --
        tearDownCycleData(jdbc);
    }

    private void tearDownCycleData(JdbcTemplate jdbc) {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            if (deviceId != null) {
                jdbc.update("DELETE FROM devices WHERE id = ?", deviceId);
                deviceId = null;
            }
            if (tournamentId != null) {
                jdbc.update(
                        "DELETE FROM match WHERE phase_id IN"
                                + " (SELECT id FROM phase WHERE tournament_id = ?)",
                        tournamentId);
                jdbc.update(
                        "DELETE FROM phase_lifecycle_job WHERE tournament_id = ?", tournamentId);
                jdbc.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
                jdbc.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
                jdbc.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
                jdbc.update("DELETE FROM tournament WHERE id = ?", tournamentId);
                tournamentId = null;
                phaseId = null;
            }
            if (locationId != null) {
                jdbc.update("DELETE FROM locations WHERE id = ?", locationId);
                locationId = null;
            }
        } finally {
            jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }
}
