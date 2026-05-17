// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.e2e;

import de.vvwt.tm.TournamentManagerApplication;
import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.PhasePreparationService;
import de.vvwt.tm.tournament.RoundAssignmentService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end integration test for TM slot-optimization Leg-3 fallback (E63S08).
 *
 * <p>Covers {@code AC-TEST-E2E-LEG3-FALLBACK}: when {@code tm.slotopt.dispatcher.url} is absent
 * (null), {@link SlotOptimizationClient#optimize(UUID)} routes to Leg 3 (cancelable in-process
 * optimization). Asserts that after the call returns, all matches in the phase have non-null {@code
 * lap_number} and {@code field_number}.
 *
 * <p>This test class is separate from {@link SlotOptE2EIT} because the Spring context must be
 * loaded WITHOUT a dispatcher URL to exercise the "dispatcher NOT configured" path in {@code
 * DefaultDispatcherReachabilityService}. {@code @DynamicPropertySource} in {@link SlotOptE2EIT}
 * sets the URL, so both classes cannot share a context.
 *
 * <h2>Leg-3 routing guarantee (DEC-15)</h2>
 *
 * <p>Per DEC-15 (offline-operability), when the dispatcher URL is not configured, TM must complete
 * slot optimization in-process without any network dependency. The {@code
 * RoutingSlotOptimizationClient} routes directly to {@code CancelableInProcessSlotOptimizationService}
 * (Leg 3) when {@code DispatcherReachabilityService.isReachable()} returns {@code false} due to a
 * null/blank URL.
 *
 * <h2>Large phase ({@code lapCount=11} — boundary routing)</h2>
 *
 * <p>The test inserts a 12-team / 1-group phase whose round-robin match generation produces 11 laps
 * (one above the {@code tm.slotopt.exhaustive-max-n=10} threshold). This forces the router to
 * check dispatcher reachability — and since the URL is absent, it falls through to Leg 3. {@code
 * lapCount=11} exercises the exact boundary condition (one above threshold) per the E63S08 edge
 * case table.
 *
 * @see SlotOptE2EIT
 * @see TmSlotOptE2ETestSupport
 * @see <a href="../../../../../../../../../docs/governance/stories/E63S08.story.md">E63S08</a>
 */
@Tag("e2e")
@SpringBootTest(
        classes = TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            // No tm.slotopt.dispatcher.url → DefaultDispatcherReachabilityService returns
            // NOT_CONFIGURED → RoutingSlotOptimizationClient routes to Leg 3
            "spring.datasource.url=jdbc:h2:mem:slotoptleg3fallbackit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "tm.slotopt.fallback.field-count=2",
            "tm.slotopt.exhaustive-max-n=10",
        })
@ActiveProfiles("test")
@Import(TenantContextTestSupport.class)
@DisplayName("SlotOptLeg3FallbackIT — E63S08 AC-TEST-E2E-LEG3-FALLBACK")
class SlotOptLeg3FallbackIT {

    @Autowired private SlotOptimizationClient slotOptimizationClient;
    @Autowired private PhasePreparationService phasePreparationService;
    @Autowired private RoundAssignmentService roundAssignmentService;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private UUID tournamentId;
    private UUID phaseId;

    @BeforeEach
    void setUp() {
        tenantBinder.bindDefaultTenant();
        tournamentId = UUID.randomUUID();
        phaseId = TmSlotOptE2ETestSupport.buildAndPersistLargePhase(tournamentId, jdbcTemplate);
        // Generate 11 laps (N−1 for N=12 round-robin) → lapCount > threshold → Leg 3 (no URL)
        phasePreparationService.generateMatches(phaseId, "roundRobin");
        // L1 baseline assignment
        roundAssignmentService.assignRoundsAndFields(phaseId, 2);
    }

    @AfterEach
    void tearDown() {
        TmSlotOptE2ETestSupport.cleanUpTournament(tournamentId, jdbcTemplate);
        tenantBinder.unbind();
    }

    /**
     * AC-TEST-E2E-LEG3-FALLBACK: with no dispatcher URL configured, {@code optimize()} routes to
     * Leg 3 (cancelable in-process) and produces non-null lap/field assignments.
     *
     * <p>Verifies DEC-15 offline-operability: TM can fully optimize a large phase without any
     * network access. The routing client checks {@code DispatcherReachabilityService.isReachable()};
     * since the URL is absent, it returns {@code false} immediately (null-URL fast path) and Leg 3
     * executes.
     *
     * <p>After {@code optimize()} returns, all 55 matches (11 laps × 5 matches/lap for 12 teams
     * round-robin) must have non-null {@code lap_number} and {@code field_number}.
     */
    @Test
    @DisplayName(
            "AC-TEST-E2E-LEG3-FALLBACK: no dispatcher URL → Leg-3 in-process optimization"
                    + " completes with non-null lap+field")
    void leg3_fallback_optimizesOffline() {
        // When: optimize() with no dispatcher URL configured
        slotOptimizationClient.optimize(phaseId);

        // Then: all matches have non-null lap_number + field_number (Leg 3 completed successfully)
        TmSlotOptE2ETestSupport.assertMatchesOptimized(phaseId, jdbcTemplate);
    }
}
