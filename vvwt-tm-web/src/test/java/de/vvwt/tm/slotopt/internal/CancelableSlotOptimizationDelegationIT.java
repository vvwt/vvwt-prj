// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.vvwt.slotopt.worker.score.VarietyScorer;
import de.vvwt.slotopt.worker.types.CanonicalPhaseDef;
import de.vvwt.tm.slotopt.CancelableInProcessSlotOptimizationService;
import de.vvwt.tm.slotopt.CancellationToken;
import de.vvwt.tm.slotopt.MappingResult;
import de.vvwt.tm.slotopt.PhaseToRawPhaseDefMapper;
import de.vvwt.tm.tenant.TenantContextTestSupport;
import de.vvwt.tm.tournament.PhasePreparationService;
import de.vvwt.tm.tournament.RoundAssignmentService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * RED-first IT: {@link DefaultCancelableInProcessSlotOptimizationService} must produce the
 * global-minimum result after delegation to {@link LapPermutationOptimizer} (E54S13).
 *
 * <h2>AC coverage</h2>
 *
 * <ul>
 *   <li>AC-TEST-CANCELABLE-DELEGATES-CORRECTLY-RED
 * </ul>
 *
 * <h2>DEC compliance</h2>
 *
 * <ul>
 *   <li>DEC-22 — Q-1a fresh-RED (written before refactor)
 *   <li>DEC-44 — {@code @SpringBootTest(RANDOM_PORT, classes = TournamentManagerApplication.class)}
 *   <li>AC-SECURITY-NO-PII-IN-NEW-TESTS: synthetic IDs and team names
 *   <li>AC-SECURITY-NO-TENANT-BLEED: tenant context bound/unbound in BeforeEach/AfterEach
 * </ul>
 *
 * @see DefaultCancelableInProcessSlotOptimizationService
 * @see LapPermutationOptimizer
 * @see <a href="E54S13">E54S13</a>
 */
@SpringBootTest(
        classes = de.vvwt.tm.TournamentManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:cancelabledelegationit;DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
            "tm.slotopt.fallback.field-count=3",
            "tm.slotopt.exhaustive-max-n=10",
            "tm.slotopt.scorer=mean"
        })
@ActiveProfiles("test")
@Import({TenantContextTestSupport.class, CancelableSlotOptimizationDelegationIT.TestConfig.class})
@DisplayName("CancelableSlotOptimizationDelegationIT — E54S13 — Cancelable path must delegate")
class CancelableSlotOptimizationDelegationIT {

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        SimpMessagingTemplate testSimpMessagingTemplate() {
            return mock(SimpMessagingTemplate.class);
        }
    }

    @Autowired private PhasePreparationService phasePreparationService;
    @Autowired private RoundAssignmentService roundAssignmentService;
    @Autowired private CancelableInProcessSlotOptimizationService cancelableService;
    @Autowired private PhaseToRawPhaseDefMapper phaseToRawPhaseDefMapper;
    @Autowired private TenantContextTestSupport.Binder tenantBinder;
    @Autowired private JdbcTemplate jdbcTemplate;

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
                "E54S13 Cancelable IT Location");
    }

    @AfterEach
    void tearDown() {
        tenantBinder.unbind();
    }

    /**
     * AC-TEST-CANCELABLE-DELEGATES-CORRECTLY-RED: Cancelable service must find global-minimum
     * permutation for 12T/2G/3F fixture with NON-cancelled token (direct invocation).
     *
     * <p>Uses 12T/2G/3F (lapCount=10, fieldCount=3). Pre-refactor stale formula:
     * rowCount/fieldCount = 10/3 = 3 (wrong). With lapCount_stale=3, the stale rowSeq expansion
     * pi[i/3]*3 + i%3 for i ≥ 9 accesses pi[3] → ArrayIndexOutOfBoundsException (bug-class 4).
     * Post-refactor: delegation to LapPermutationOptimizer → correct lapCount=10 → 10! search.
     *
     * <p>NOTE: 12T/2G/3F has lapCount=10 = exhaustiveMaxN. RoutingSlotOptimizationClient routes
     * this to Leg 1 (lapCount ≤ threshold). This test calls the cancelable service DIRECTLY,
     * bypassing routing, to verify its own correctness.
     */
    @Test
    void optimize_12T2G3F_CancelableNotCancelled_DelegatesCorrectly_E54S13() {
        setUp12T2G3FPhaseWithAvatars();

        // L1: match generation
        phasePreparationService.generateMatches(phaseId, "roundRobin");

        // L2: round assignment (3 fields for 12T/2G/3F)
        roundAssignmentService.assignRoundsAndFields(phaseId, 3);

        // Capture post-L2 active-matrix
        MappingResult postL2Mapping = phaseToRawPhaseDefMapper.map(phaseId);
        CanonicalPhaseDef canonical = postL2Mapping.canonical();
        int lapCount = canonical.rowCount();
        int avatarCount = canonical.avatarCount();

        boolean[][] activeMatrix =
                buildActiveMatrixFromRawRows(
                        postL2Mapping.denseIdsByRawRow(), lapCount, avatarCount);

        // Independent brute-force (Heap's algorithm)
        VarietyScorer testScorer = new VarietyScorer();
        double testBestScore = Double.MAX_VALUE;
        int[] testBestPermutation = null;
        int[] perm = new int[lapCount];
        for (int i = 0; i < lapCount; i++) perm[i] = i;

        double score = testScorer.scoreWithMatrix(perm, lapCount, avatarCount, activeMatrix);
        if (score < testBestScore) {
            testBestScore = score;
            testBestPermutation = perm.clone();
        }

        int[] c = new int[lapCount];
        int i = 0;
        while (i < lapCount) {
            if (c[i] < i) {
                if (i % 2 == 0) {
                    int tmp = perm[0];
                    perm[0] = perm[i];
                    perm[i] = tmp;
                } else {
                    int tmp = perm[c[i]];
                    perm[c[i]] = perm[i];
                    perm[i] = tmp;
                }
                score = testScorer.scoreWithMatrix(perm, lapCount, avatarCount, activeMatrix);
                if (score < testBestScore) {
                    testBestScore = score;
                    testBestPermutation = perm.clone();
                }
                c[i]++;
                i = 0;
            } else {
                c[i] = 0;
                i++;
            }
        }

        // Cancelable optimization with NON-cancelled token
        CancellationToken token = CancellationToken.create(); // not cancelled

        cancelableService.optimize(phaseId, tournamentId, token);

        // Score post-cancelable DB state
        MappingResult postCancelableMapping = phaseToRawPhaseDefMapper.map(phaseId);
        boolean[][] postCancelableActiveMatrix =
                buildActiveMatrixFromRawRows(
                        postCancelableMapping.denseIdsByRawRow(), lapCount, avatarCount);

        int[] identityPerm = new int[lapCount];
        for (int j = 0; j < lapCount; j++) identityPerm[j] = j;
        double prodScore =
                testScorer.scoreWithMatrix(
                        identityPerm, lapCount, avatarCount, postCancelableActiveMatrix);

        assertThat(prodScore)
                .as(
                        "AC-TEST-CANCELABLE-DELEGATES-CORRECTLY-RED (E54S13): "
                                + "Cancelable path score=%.6f vs brute-force optimum=%.6f "
                                + "(delta=%.6f); testBestPermutation=%s. "
                                + "DefaultCancelableInProcessSlotOptimizationService must delegate"
                                + " to LapPermutationOptimizer.",
                        prodScore,
                        testBestScore,
                        Math.abs(prodScore - testBestScore),
                        Arrays.toString(testBestPermutation))
                .isLessThanOrEqualTo(testBestScore + 1e-9);
    }

    // =========================================================================
    // Setup helpers
    // =========================================================================

    /**
     * Sets up 12T/2G/3F (12 teams, 2 groups of 6, 3 fields). lapCount=10. Stale formula:
     * rowCount/fieldCount = 10/3 = 3 (WRONG). Causes ArrayIndexOutOfBoundsException in pre-refactor
     * code when rowSeq expansion accesses pi[3] for i=9 (pi has length 3).
     */
    private void setUp12T2G3FPhaseWithAvatars() {
        tournamentId = UUID.randomUUID();
        phaseId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize, draft_json)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "E54S13 Cancelable IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "PLANNED",
                LocalDateTime.now(),
                3, // 3 fields
                12, // 12 teams
                true,
                "{\"sections\": ["
                        + "  {\"sectionNumber\": 1, \"sortType\": \"team_number\","
                        + "   \"groupCount\": 2, \"gameMode\": \"roundRobin\","
                        + "   \"lapBreakTimeMinutes\": 0, \"sectionBreakTimeMinutes\": 0,"
                        + "   \"lapTimeMinutes\": 15, \"setQuantity\": 3, \"breaks\": []}"
                        + "]}");

        jdbcTemplate.update(
                "INSERT INTO phase (id, tournament_id, sequence_number, description,"
                        + " status, current_lap_number, created_at, optimized)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                phaseId,
                tournamentId,
                1,
                "Phase 1",
                "PREPARED",
                0,
                LocalDateTime.now(),
                false);

        // 12 teams
        UUID[] teamIds = new UUID[12];
        for (int t = 0; t < 12; t++) {
            teamIds[t] = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    teamIds[t],
                    tournamentId,
                    t + 1,
                    "Team " + (t + 1),
                    true,
                    LocalDateTime.now());
        }

        // 12 avatars: group 1 for t=0..5, group 2 for t=6..11
        for (int t = 0; t < 12; t++) {
            int group = (t < 6) ? 1 : 2;
            int pos = (t < 6) ? t + 1 : (t - 6) + 1;
            jdbcTemplate.update(
                    "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                            + " group_position, team_id) VALUES (?, ?, ?, ?, ?, NULL)",
                    UUID.randomUUID(),
                    tournamentId,
                    phaseId,
                    group,
                    pos);
        }
    }

    private static boolean[][] buildActiveMatrixFromRawRows(
            int[][] denseIdsByRawRow, int rowCount, int avatarCount) {
        boolean[][] activeMatrix = new boolean[rowCount][avatarCount];
        for (int lapIndex = 0; lapIndex < rowCount; lapIndex++) {
            for (int avatarId : denseIdsByRawRow[lapIndex]) {
                activeMatrix[lapIndex][avatarId] = true;
            }
        }
        return activeMatrix;
    }
}
