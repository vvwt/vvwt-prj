// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-support helper for TM slot-optimization end-to-end integration tests.
 *
 * <p>Provides DB fixture setup for large phases (lapCount {@literal >} 10 — above the exhaustive
 * routing threshold) and post-optimize assertion helpers.
 *
 * <h2>Large-phase construction</h2>
 *
 * <p>{@link #buildAndPersistLargePhase(UUID, JdbcTemplate)} inserts a tournament + phase with 8
 * avatars in 1 group and 22 matches directly (11 laps × 2 fields). The matches are inserted with
 * {@code lapNumber} in [1..11] and {@code fieldNumber} in [1..2] — this bypasses the real match
 * generator and round-assignment pipeline, which is not needed for E2E optimization tests.
 *
 * <p>lapCount=11 is one above the {@code tm.slotopt.exhaustive-max-n=10} threshold, which forces
 * Leg 2 (dispatcher) or Leg 3 (offline) routing in {@code RoutingSlotOptimizationClient}.
 *
 * <h2>Why inline match insertion (not generateMatches + assignRoundsAndFields)</h2>
 *
 * <p>The real round-robin generator for 12 teams produces N*(N-1)/2=66 matches. With {@code
 * fieldCount=2}, the greedy assignment produces 66/2=33 laps (33 matches-per-field × 1 field each).
 * That far exceeds the {@code tm.slotopt.exhaustive-max-n} threshold, but also exceeds 17! overflow
 * limit for the brute-force solver. Inserting matches directly with {@code lapNumber} pre-set to
 * [1..11] gives precise control over lapCount.
 *
 * <h2>Database isolation</h2>
 *
 * <p>Each test class uses a dedicated H2 in-memory database (via the {@code spring.datasource.url}
 * property on the {@code @SpringBootTest} annotation) so there is no inter-test contamination.
 *
 * @see SlotOptE2EIT
 * @see SlotOptLeg3FallbackIT
 */
final class TmSlotOptE2ETestSupport {

    /**
     * Number of laps in the inserted phase fixture (one above the threshold=10, per E63S08 edge
     * case table: boundary routing at exactly one above the exhaustive threshold).
     */
    static final int LAP_COUNT = 11;

    /**
     * Number of fields per lap in the inserted phase fixture (controls {@code fieldCount} used by
     * the mapper and the tournament record).
     */
    static final int FIELD_COUNT = 2;

    private TmSlotOptE2ETestSupport() {
        // utility class
    }

    /**
     * Inserts a tournament + phase + avatars + matches into the H2 in-memory database.
     *
     * <p>Inserts {@value #LAP_COUNT} laps × {@value #FIELD_COUNT} fields = {@code LAP_COUNT *
     * FIELD_COUNT} matches with {@code lapNumber} in [1..LAP_COUNT] and {@code fieldNumber} in
     * [1..FIELD_COUNT]. Uses 4 avatars in 1 group cycling across laps to avoid avatar conflicts
     * within a lap (each avatar appears at most once per lap, per DEC-9 structural identity).
     *
     * <p>The avatar cycle: lap 1→avatars 0,1,2,3; lap 2→avatars 2,3,0,1; lap 3→avatars 0,1,2,3;
     * etc. — a simple alternating assignment ensuring no avatar conflict within any single lap.
     *
     * <p>Matches are inserted with non-null {@code lapNumber} and {@code fieldNumber} — this
     * represents the L2 baseline (post-round-assignment, pre-Leg-2/Leg-3 slot-optimization). {@link
     * de.vvwt.tm.slotopt.SlotOptimizationClient#optimize(UUID)} will then re-permute the laps to
     * find the optimal ordering.
     *
     * @param tournamentId the tournament UUID to use (caller supplies for traceability)
     * @param jdbcTemplate the JDBC template for the tenant-bound H2 database
     * @return the inserted phase UUID
     */
    static UUID buildAndPersistLargePhase(UUID tournamentId, JdbcTemplate jdbcTemplate) {
        UUID locationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO locations (id, display_name) VALUES (?, ?)",
                locationId,
                "E63S08 E2E IT Location");

        // Tournament: field_count=FIELD_COUNT, team_count arbitrary (not used by mapper)
        jdbcTemplate.update(
                "INSERT INTO tournament (id, location_id, description, match_format,"
                        + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                        + " status, created_at, field_count, team_count, optimize)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tournamentId,
                locationId,
                "E63S08 E2E IT Tournament",
                "BEST_OF_3",
                "setPoints",
                "standardVolleyball",
                "roundRobin",
                "PLANNED",
                LocalDateTime.now(),
                FIELD_COUNT, // field_count — used by PhaseToRawPhaseDefMapper.getFieldCount()
                8, // team_count (informational; we insert 4 avatars directly)
                true);

        UUID phaseId = UUID.randomUUID();
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

        // Insert 4 avatars in 1 group (group_number=1, group_position=1..4)
        // Each avatar has a corresponding team (required by team_avatar FK on team_id, but we
        // insert team_id=NULL per DEC-59 Clause B — avatar identity is structural, not team-based)
        UUID[] avatarIds = new UUID[4];
        for (int i = 0; i < 4; i++) {
            avatarIds[i] = UUID.randomUUID();
            UUID teamId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO team (id, tournament_id, team_number, description, participate,"
                            + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    teamId,
                    tournamentId,
                    i + 1,
                    "Team " + (i + 1),
                    true,
                    LocalDateTime.now());
            // team_id=NULL per DEC-59 Clause B (structural identity, not team-based)
            jdbcTemplate.update(
                    "INSERT INTO team_avatar (id, tournament_id, phase_id, group_number,"
                            + " group_position, team_id) VALUES (?, ?, ?, ?, ?, NULL)",
                    avatarIds[i],
                    tournamentId,
                    phaseId,
                    1,
                    i + 1);
        }

        // Insert LAP_COUNT × FIELD_COUNT matches with pre-assigned lap_number and field_number.
        // Avatar assignment per lap: avatarIds cycle to ensure no avatar appears twice in one lap.
        // Pairing:
        //   field 1 in any lap: avatarIds[lapIndex % 2 * 2] vs avatarIds[lapIndex % 2 * 2 + 1]
        //   field 2 in any lap: avatarIds[(1 - lapIndex % 2) * 2] vs
        //                        avatarIds[(1 - lapIndex % 2) * 2 + 1]
        // This ensures each lap uses all 4 avatars exactly once across 2 matches.
        // Pattern: even laps: (0vs1 on field1, 2vs3 on field2); odd laps: (2vs3 on field1,
        // 0vs1 on field2). No avatar conflict within any lap.
        for (int lap = 1; lap <= LAP_COUNT; lap++) {
            int offset = (lap % 2 == 1) ? 0 : 2; // alternate pairing order per lap
            for (int field = 1; field <= FIELD_COUNT; field++) {
                int a1 = (offset + (field - 1) * 2) % 4;
                int a2 = (offset + (field - 1) * 2 + 1) % 4;
                jdbcTemplate.update(
                        "INSERT INTO match (id, tournament_id, phase_id, member_avatar_1_id,"
                                + " member_avatar_2_id, state, set_limit, lap_number,"
                                + " field_number, created_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        UUID.randomUUID(),
                        tournamentId,
                        phaseId,
                        avatarIds[a1],
                        avatarIds[a2],
                        0, // MatchState.OPEN.getLegacyCode()
                        3, // setLimit
                        lap,
                        field,
                        LocalDateTime.now());
            }
        }

        return phaseId;
    }

    /**
     * Asserts that every match in the given phase has a non-null {@code lap_number} and a non-null
     * {@code field_number} after slot optimization completes.
     *
     * <p>This assertion covers AC-TEST-E2E-LEG2-LIVE-WORKER,
     * AC-TEST-E2E-EMBEDDED-WORKER-CONTRIBUTES, and AC-TEST-E2E-LEG3-FALLBACK: in all three legs the
     * postcondition is the same — every match has non-null slot coordinates.
     *
     * @param phaseId the phase to verify
     * @param jdbcTemplate the JDBC template for the tenant-bound H2 database
     */
    static void assertMatchesOptimized(UUID phaseId, JdbcTemplate jdbcTemplate) {
        int totalMatches =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ?", Integer.class, phaseId);
        assertThat(totalMatches)
                .as("phase %s must have at least 1 match after optimization", phaseId)
                .isGreaterThan(0);

        int nullLapCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ? AND lap_number IS NULL",
                        Integer.class,
                        phaseId);
        assertThat(nullLapCount)
                .as(
                        "all matches in phase %s must have a non-null lap_number after optimize()"
                                + " — found %d matches with null lap_number out of %d total",
                        phaseId, nullLapCount, totalMatches)
                .isZero();

        int nullFieldCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM match WHERE phase_id = ? AND field_number IS NULL",
                        Integer.class,
                        phaseId);
        assertThat(nullFieldCount)
                .as(
                        "all matches in phase %s must have a non-null field_number after"
                                + " optimize() — found %d matches with null field_number out of"
                                + " %d total",
                        phaseId, nullFieldCount, totalMatches)
                .isZero();
    }

    /**
     * Deletes all test data for a tournament and its location from the H2 in-memory DB.
     *
     * <p>Disables referential integrity during deletion for simplicity (H2-compatible).
     *
     * @param tournamentId the tournament UUID whose data should be removed
     * @param jdbcTemplate the JDBC template for the tenant-bound H2 database
     */
    static void cleanUpTournament(UUID tournamentId, JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            jdbcTemplate.update(
                    "DELETE FROM match WHERE phase_id IN"
                            + " (SELECT id FROM phase WHERE tournament_id = ?)",
                    tournamentId);
            jdbcTemplate.update("DELETE FROM team_avatar WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM team WHERE tournament_id = ?", tournamentId);
            jdbcTemplate.update("DELETE FROM phase WHERE tournament_id = ?", tournamentId);
            String locationId =
                    jdbcTemplate.queryForObject(
                            "SELECT CAST(location_id AS VARCHAR) FROM tournament WHERE id = ?",
                            String.class,
                            tournamentId);
            jdbcTemplate.update("DELETE FROM tournament WHERE id = ?", tournamentId);
            if (locationId != null) {
                jdbcTemplate.update("DELETE FROM locations WHERE id = ?", locationId);
            }
        } finally {
            jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }
}
