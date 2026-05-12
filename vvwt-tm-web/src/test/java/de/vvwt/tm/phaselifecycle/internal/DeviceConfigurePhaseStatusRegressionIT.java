package de.vvwt.tm.phaselifecycle.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.DeviceService;
import de.vvwt.tm.tournament.PhaseLifecycleService;
import java.time.LocalDateTime;
import java.util.UUID;
import javax.sql.DataSource;
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
 * Regression IT for M-2 (phase.status ACTIVE→ASSIGNED regression) —
 * AC-TEST-M-2-PHASE-STATUS-NO-REGRESSION-RED.
 *
 * <h2>M-2 regression background</h2>
 *
 * <p>Under the E1 (events-only) architecture, {@code Device.configure()} triggered a
 * {@code @TransactionalEventListener(AFTER_COMMIT)} listener that issued a phase-status write in a
 * separate transaction. On concurrent execution, this listener could overwrite the ACTIVE phase
 * status back to ASSIGNED, causing the "ACTIVE→ASSIGNED regression" observed 2026-05-11.
 *
 * <p>The E3 orchestrator (DEC-64) eliminates this by removing the event-driven cascade. In E3,
 * {@code Device.configure()} does not publish any event affecting phase status. This IT verifies
 * that after starting a phase (ASSIGNED→ACTIVE) and immediately calling {@code Device.configure()},
 * the phase remains ACTIVE.
 *
 * <p>Authorizing decisions: DEC-22 (GREEN-on-E3: regression surface structurally absent per Session
 * Brief §M-2, timestamp 2026-05-11), DEC-44 (NONE), AC-TEST-M-2-PHASE-STATUS-NO-REGRESSION-RED
 * (E55S07).
 *
 * @since E55S07
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:m2regressionit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName(
        "DeviceConfigurePhaseStatusRegressionIT — AC-TEST-M-2-PHASE-STATUS-NO-REGRESSION-RED"
                + " (E55S07)")
class DeviceConfigurePhaseStatusRegressionIT {

    @Autowired private PhaseLifecycleService phaseLifecycleService;
    @Autowired private DeviceService deviceService;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;

    private UUID locationId;
    private UUID tournamentId;
    private UUID phaseId;
    private UUID deviceId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();

        locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "M2 Regression IT Location");

        // Register a DISPLAY device for configure() testing
        var device = deviceService.register("DISPLAY");
        deviceId = device.getId();

        tournamentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "M2 Regression IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "PLANNED",
                LocalDateTime.now(),
                2,
                4,
                false); // optimize=false so ASSIGNED→ACTIVE is not blocked by activation-guard

        phaseId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description, status,"
                        + " current_lap_number, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "ASSIGNED",
                0,
                true); // optimized=true so activation-guard allows start
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
            if (deviceId != null) {
                jdbcTemplate.update("DELETE FROM devices WHERE id = ?", deviceId);
            }
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
            tenantBinder.unbind();
        }
    }

    /**
     * AC-TEST-M-2-PHASE-STATUS-NO-REGRESSION-RED: start phase (ASSIGNED→ACTIVE), then call
     * Device.configure() — phase.status must remain ACTIVE.
     *
     * <p>On E1 codebase: Device.configure() triggered a listener that reverted phase to ASSIGNED.
     * On E3 codebase: Device.configure() does not affect phase status — test passes GREEN.
     */
    @Test
    @DisplayName(
            "M-2 regression: Device.configure does NOT revert phase.status from ACTIVE to ASSIGNED"
                    + " (E3 arch eliminates listener cascade)")
    void deviceConfigureDoesNotRevertActivePhaseStatus() {
        // Start the phase: ASSIGNED → ACTIVE via start() (includes E48S24 D-1a auto-promote)
        phaseLifecycleService.start(phaseId);

        // Verify phase is ACTIVE before configure
        String statusBeforeConfigure =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase WHERE id = ?", String.class, phaseId);
        assertThat(statusBeforeConfigure)
                .as("phase must be ACTIVE after start transition")
                .isEqualTo("ACTIVE");

        // Issue Device.configure (the operation that historically triggered M-2 regression)
        deviceService.configure(deviceId, "Court-1-Display", "{\"brightness\": 80}");

        // Assert: phase.status is still ACTIVE — no regression
        String statusAfterConfigure =
                jdbcTemplate.queryForObject(
                        "SELECT status FROM phase WHERE id = ?", String.class, phaseId);
        assertThat(statusAfterConfigure)
                .as(
                        "M-2 regression check: phase.status must remain ACTIVE after"
                                + " Device.configure (E3 arch eliminates listener cascade; surface"
                                + " absent since 2026-05-11)")
                .isEqualTo("ACTIVE");
    }
}
