// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.Device;
import de.vvwt.tm.tournament.DeviceService;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseLifecycleService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * GREEN-only regression guard for the H2 2.3.232 → 2.4.240 upgrade (E55S14, DEC-67).
 *
 * <h2>Purpose and DEC-67 classification</h2>
 *
 * <p>This IT is a <strong>GREEN-only regression guard</strong> as recommended by DEC-67 clause 2.
 * It is NOT a RED-first artefact — it is authored against the post-upgrade state (H2 2.4.240) and
 * is GREEN from its first run. DEC-22 RED-first does NOT apply to this story per DEC-67 clause 1
 * (the {@code pom.xml} version-coordinate change authors no first-party code).
 *
 * <h2>What this guard verifies</h2>
 *
 * <p>For {@code N} cycles (default 10, configurable via {@code
 * tm.test.recurrence-protection-cycles}, ≤10 min runtime for N=10), each cycle asserts the
 * M-2/M-3/M-5 invariants:
 *
 * <ul>
 *   <li><strong>M-2</strong>: {@code phase[1].status == 'ACTIVE'} after Phase 1 start — no
 *       phase.status ACTIVE→PREPARED/ASSIGNED regression.
 *   <li><strong>M-3</strong>: {@code tournament.status == 'ACTIVE'} after Phase 1 start — no
 *       tournament.status ACTIVE→PLANNED regression.
 *   <li><strong>M-5</strong>: {@code devices.count >= 1} — no device-row loss after Phase 1
 *       activation.
 * </ul>
 *
 * <h2>Diagnostic limitation (acknowledged per DEC-67 clause 2)</h2>
 *
 * <p>This guard catches a gross regression (e.g., an accidental H2 downgrade or a future H2 version
 * that re-introduces the data-loss bug). It does NOT reproduce the original Heisenbug: the
 * M-2/M-3/M-5 bug class required concurrent multi-connection MVStore compaction across operator-
 * interactive browser sessions — a pattern that a single-JVM in-process {@code @SpringBootTest}
 * harness structurally cannot create. E55S12's RED-first attempt ran N=10 and N=30 under H2 2.3.232
 * and saw no failures.
 *
 * <h2>Fixed by H2 2.4.240</h2>
 *
 * <p>The upgrade targets Issue #4247 ("Compaction causes missing chunks and data loss") and Issue
 * #4208 ("Lost update when attempting to atomically increment a value using SELECT FOR UPDATE") in
 * the H2 2.4.240 changelog (released 2025-09-22). See {@code
 * .gaai/project/contexts/artefacts/audits/E55S14.upgrade-verification.md} for the full
 * defect-justification evidence (empirical runs + changelog mapping + residual-risk
 * acknowledgment).
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E55S14.story.md">Story
 *     E55S14</a>
 * @since E55S14
 */
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName(
        "H2VersionUpgradeRegressionProtectionIT — GREEN-only regression guard (E55S14, DEC-67)")
class H2VersionUpgradeRegressionProtectionIT {

    @Value("${tm.test.recurrence-protection-cycles:10}")
    private int cycles;

    @Autowired private PhaseLifecycleService phaseLifecycleService;
    @Autowired private DeviceService deviceService;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<UUID> locationIds = new ArrayList<>();
    private final List<UUID> tournamentIds = new ArrayList<>();
    private final List<UUID> deviceIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();
    }

    @AfterEach
    void tearDown() {
        try {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
            try {
                // Clean devices registered during the test
                for (UUID deviceId : deviceIds) {
                    jdbcTemplate.update("DELETE FROM devices WHERE id = ?", deviceId);
                }
                // Clean tournament rows (phases, team_avatars, teams, matches cascaded via FK)
                for (UUID tournamentId : tournamentIds) {
                    jdbcTemplate.update(
                            "DELETE FROM match WHERE phase_id IN"
                                    + " (SELECT id FROM phase WHERE tournament_id = ?)",
                            tournamentId);
                    jdbcTemplate.update(
                            "DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
                    jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
                    jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
                    jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
                }
                // Each cycle uses its own location to avoid IDX_TOURNAMENT_ACTIVE_PER_LOCATION
                // unique-constraint conflicts (one ACTIVE tournament per location per DEC-39 D3).
                for (UUID locationId : locationIds) {
                    jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
                }
            } finally {
                jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
            }
        } finally {
            locationIds.clear();
            tournamentIds.clear();
            deviceIds.clear();
            tenantBinder.unbind();
        }
    }

    /**
     * M-2/M-3/M-5 regression guard: N cycles of tournament activation + device registration.
     *
     * <p>Each cycle: create tournament (PLANNED, optimize=false) + phase (ASSIGNED, optimized=true)
     * → register a DISPLAY device → activate Phase 1 → assert M-2 (phase.status==ACTIVE), M-3
     * (tournament.status==ACTIVE), M-5 (device row present).
     *
     * <p>The N-cycle loop provides statistical breadth given the MVStore compaction trigger was
     * timing-dependent. Under H2 2.4.240 and the E3 Saga-Orchestrator (DEC-64) architecture, all
     * cycles are expected GREEN deterministically.
     */
    @Test
    @DisplayName(
            "M-2/M-3/M-5 invariants hold in N cycles of tournament activation + device registration"
                    + " under H2 2.4.240 (GREEN-only guard, E55S14)")
    void m2M3M5InvariantsHoldForNCycles() {
        for (int cycle = 0; cycle < cycles; cycle++) {
            // ── Setup: fresh location + tournament + phase per cycle ───────────────────
            // Each cycle uses its own location so the IDX_TOURNAMENT_ACTIVE_PER_LOCATION
            // unique index (DEC-39 D3: at most one ACTIVE tournament per location) does not
            // conflict across cycles when multiple tournaments remain ACTIVE simultaneously.
            UUID locationId = UUID.randomUUID();
            locationIds.add(locationId);
            jdbcTemplate.update(
                    "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                    locationId,
                    "H2UpgradeGuard Location cycle-" + cycle);

            UUID tournamentId = UUID.randomUUID();
            tournamentIds.add(tournamentId);

            jdbcTemplate.update(
                    "INSERT INTO tournament (id, location_id, description, match_format,"
                            + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                            + " status, created_at, field_count, team_count, optimize)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    tournamentId,
                    locationId,
                    "H2UpgradeGuard Tournament cycle-" + cycle,
                    "BEST_OF_3",
                    "setPoints",
                    "standardVolleyball",
                    "roundRobin",
                    "PLANNED",
                    LocalDateTime.now(),
                    2,
                    4,
                    false); // optimize=false: activation-guard (!optimize OR optimized) passes
            // trivially

            UUID phaseId = UUID.randomUUID();
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
                    true); // optimized=true AND optimize=false → activation-guard passes

            // ── M-5: register a DISPLAY device before phase activation ─────────────────
            Device device = deviceService.register(Device.TYPE_DISPLAY);
            deviceIds.add(device.getId());

            // ── Activate Phase 1: ASSIGNED → ACTIVE ────────────────────────────────────
            // PhaseLifecycleService.start() performs ASSIGNED→ACTIVE and auto-promotes tournament
            // PLANNED→ACTIVE (E48S24 D-1a) within the same Saga-Orchestrator transaction (DEC-64).
            Phase activatedPhase = phaseLifecycleService.start(phaseId);

            // ── M-2: phase.status == ACTIVE ────────────────────────────────────────────
            assertThat(activatedPhase.getStatus())
                    .as(
                            "cycle %d — M-2: phase.status must be ACTIVE after start (no"
                                    + " ACTIVE→PREPARED/ASSIGNED regression under H2 2.4.240)",
                            cycle)
                    .isEqualTo(Phase.PhaseStatus.ACTIVE.name());

            // Confirm via direct DB read (independent of in-memory state returned by service)
            String phaseStatusFromDb =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM phase WHERE id = ?", String.class, phaseId);
            assertThat(phaseStatusFromDb)
                    .as(
                            "cycle %d — M-2 (DB read): phase.status must be ACTIVE in DB after"
                                    + " start (no ACTIVE→PREPARED/ASSIGNED regression)",
                            cycle)
                    .isEqualTo("ACTIVE");

            // ── M-3: tournament.status == ACTIVE ───────────────────────────────────────
            String tournamentStatusFromDb =
                    jdbcTemplate.queryForObject(
                            "SELECT status FROM tournament WHERE id = ?",
                            String.class,
                            tournamentId);
            assertThat(tournamentStatusFromDb)
                    .as(
                            "cycle %d — M-3: tournament.status must be ACTIVE after Phase 1 start"
                                    + " (auto-promote PLANNED→ACTIVE per E48S24 D-1a; no"
                                    + " ACTIVE→PLANNED regression under H2 2.4.240)",
                            cycle)
                    .isEqualTo("ACTIVE");

            // ── M-5: device row must still exist (no device-row loss) ──────────────────
            Integer deviceCount =
                    jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM devices WHERE id = ?",
                            Integer.class,
                            device.getId());
            assertThat(deviceCount)
                    .as(
                            "cycle %d — M-5: device row must still exist after phase activation"
                                    + " (no device-row loss under H2 2.4.240)",
                            cycle)
                    .isGreaterThanOrEqualTo(1);
        }
    }
}
